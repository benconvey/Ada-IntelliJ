package com.adacore.adaintellij.lsp;

import java.io.IOException;
import java.util.*;

import com.intellij.execution.*;
import com.intellij.notification.*;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.messages.MessageBus;
import org.jetbrains.annotations.*;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageServer;

import com.adacore.adaintellij.build.*;
import com.adacore.adaintellij.editor.*;
import com.adacore.adaintellij.file.AdaFileType;
import com.adacore.adaintellij.misc.cache.Cacher;
import com.adacore.adaintellij.notifications.AdaIJNotification;
import com.adacore.adaintellij.project.*;

import static com.adacore.adaintellij.Utils.*;

/**
 * Driver handling the LSP session between Ada-IntelliJ's
 * integrated client and the Ada Language Server (ALS).
 *
 * The Ada-IntelliJ LSP integration is up-to-date with
 * protocol version 3.13.0.
 */
public final class AdaLSPDriver implements ProjectComponent {
	
	/**
	 * The number of tolerable failed requests before an LSP session is terminated.
	 */
	static final int FAILURE_COUNT_THRESHOLD = 7;
	
	/**
	 * The interval duration, in milliseconds, before checking if the operation
	 * within the IntelliJ platform that resulted in a certain request to the ALS
	 * was canceled.
	 */
	static final int CHECK_CANCELED_INTERVAL = 250;
	
	/**
	 * Unique key for registering the driver's GPR file change listener.
	 */
	private static final String GPR_FILE_CHANGE_LISTENER_KEY =
		"com.adacore.adaintellij.lsp.AdaLSPDriver@gprFileChangeListener";
	
	/**
	 * The project to which this component belongs.
	 */
	private Project project;
	
	/**
	 * The corresponding Ada project component.
	 */
	private AdaProject adaProject;
	
	/**
	 * The project's GPR file manager project component.
	 */
	private GPRFileManager gprFileManager;
	
	/**
	 * The project's GPRbuild configuration manager project component.
	 */
	private GPRbuildConfigurationManager gprbuildConfigurationManager;
	
	/**
	 * The LSP driver's client.
	 */
	private AdaLSPClient client;
	
	/**
	 * The LSP driver's server interface.
	 */
	private AdaLSPServer server;
	
	/**
	 * Whether or not the LSP session has been fully initialized.
	 * Note that, contrary to what the LSP specification implies, from the point of view
	 * of the Ada-IntelliJ plugin, the LSP session is considered to be fully initialized
	 * after the plugin's LSP client sends the first `workspace/didChangeConfiguration`
	 * notification containing a project file path, and not after it sends the
	 * `initialized` notification.
	 */
	private boolean initialized = false;
	
	/**
	 * Aggregate document change consumer operation that makes `textDocument/didChange`
	 * requests to the ALS on document change events.
	 */
	private DocumentChangeConsumerOperation documentChangeOperation;
	
	/**
	 * Constructs a new AdaLSPDriver given a project and other project components.
	 * Note that the GPRFileManager project component is a required dependency so that
	 * in case multiple GPR files are found in the project hierarchy, the user is given
	 * a chance to choose one, which would then be used to initialize the ALS.
	 *
	 * @param project The project to attach to the constructed driver.
	 * @param adaProject The Ada project component to attach to the constructed driver.
	 * @param gprFileManager The GPR file manager to attach to the constructed driver.
	 */
	public AdaLSPDriver(
		Project                      project,
		AdaProject                   adaProject,
		GPRFileManager               gprFileManager,
		GPRbuildConfigurationManager gprbuildConfigurationManager
	) {
		this.project                      = project;
		this.adaProject                   = adaProject;
		this.gprFileManager               = gprFileManager;
		this.gprbuildConfigurationManager = gprbuildConfigurationManager;
	}
	
	/*
		Project Component Open/Close Handlers
	*/
	
	/**
	 * @see com.intellij.openapi.components.ProjectComponent#projectOpened()
	 */
	@Override
	public void projectOpened() {
		
		if (!adaProject.isAdaProject()) { return; }
		
		// Get ALS path
		
		String alsPath = getPathFromSystemPath(LSPUtils.ALS_NAME, false);
		
		// If the ALS is not found on the PATH, then notify the
		// user to install the ALS and add it to their PATH
		
		if (alsPath == null) {
			
			Notifications.Bus.notify(new AdaIJNotification(
				"Ada Language Server not found on PATH",
				"In order to provide semantic features and smart assistance for Ada, the " +
					"Ada-IntelliJ plugin relies heavily on the Ada Language Server (ALS). " +
					"To enable these features, you need download and install the ALS, add " +
					"it to your PATH, then reload open projects for the effect to take place.\n" +
					"ALS binaries can be found here:\n" +
					"https://bintray.com/beta/#/reznikmm/ada-language-server/ada-language-server?tab=files",
				NotificationType.WARNING
			));
			
			return;
			
		}
		
		Process process;
		
		try {
			
			// Try to start the server process
			
			process = new ProcessBuilder(alsPath).start();
		
		} catch (IOException exception) {
			
			// Notify the user that the server could not be started
			
			Notifications.Bus.notify(new AdaIJNotification(
				"Failed to start Ada Language Server",
				"Reload the current project to try again.",
				NotificationType.ERROR
			));
			
			return;
			
		}
		
		// Connect to the server process' input/output
		
		client = new AdaLSPClient(this, project);
		
		Launcher<LanguageServer> serverLauncher = LSPLauncher.createClientLauncher(
			client, process.getInputStream(), process.getOutputStream());
		
		server = new AdaLSPServer(this, serverLauncher.getRemoteProxy());
		
		serverLauncher.startListening();
		
		// Send the `initialize` request to initialize the server
		
		InitializeResult result = server.initialize(getInitParams());
		
		if (result == null) {
			
			// Notify the user that the initialization failed
			
			Notifications.Bus.notify(new AdaIJNotification(
				"Failed to initialize Ada Language Server",
				"Reload the current project to try again.",
				NotificationType.ERROR
			));
			
			return;
			
		}
		
		server.setCapabilities(result.getCapabilities());
		
		server.initialized(new InitializedParams());
		
		// Try to set up the LSP server with the project's GPR file path
		// This may not complete in case no GPR files exist in the project
		// or in case multiple ones exist and the user has not chosen one,
		// in which case the server will not be marked as initialized and
		// no requests will be made to it until a GPR file path is set and
		// successfully communicated to the server
		
		setConfiguration();
		
		// Add a GPR file change listener and a GPRbuild configuration
		// selection/change listener in order to send workspace
		// configuration changes to the server
		
		gprFileManager.addGprFileChangeListener(
			GPR_FILE_CHANGE_LISTENER_KEY, this::setConfiguration);
		
		gprbuildConfigurationManager.addRunManagerListener(new RunManagerListener() {
			
			/**
			 * Called when a configuration is changed.
			 *
			 * @param settings The changed configuration's settings.
			 */
			@Override
			public void runConfigurationChanged(@NotNull RunnerAndConfigurationSettings settings) {
				setConfiguration();
			}
			
			/**
			 * Called when a different configuration is selected.
			 */
			@Override
			public void runConfigurationSelected() {
				setConfiguration();
			}
			
		});
		
	}
	
	/**
	 * @see com.intellij.openapi.components.ProjectComponent#projectClosed()
	 */
	@Override
	public void projectClosed() {
		
		if (!adaProject.isAdaProject()) { return; }
		
		// Shut down the server
		
		shutDownServer();
		
	}
	
	/**
	 * @see com.intellij.openapi.components.NamedComponent#getComponentName()
	 */
	@NotNull
	@Override
	public String getComponentName() { return "com.adacore.adaintellij.lsp.AdaLSPDriver"; }
	
	/**
	 * Returns the given project's LSP client.
	 *
	 * @param project The project for which to get the client.
	 * @return The given project's client.
	 */
	@Nullable
	public static AdaLSPClient getClient(@NotNull Project project) {
		return project.getComponent(AdaLSPDriver.class).client;
	}
	
	/**
	 * Returns the given project's ALS interface object.
	 *
	 * @param project The project for which to get the server.
	 * @return The given project's server.
	 */
	@Nullable
	public static AdaLSPServer getServer(@NotNull Project project) {
		return project.getComponent(AdaLSPDriver.class).server;
	}
	
	/**
	 * Shuts down the LSP server.
	 */
	void shutDownServer() {
		
		if (!initialized) { return; }
		
		// Mark the server as not initialized
		
		initialized = false;
		
		// Stop the document change operation
		
		documentChangeOperation.stop();
		
		// Send the shutdown request
		
		server.shutdown();
		
		// Send the exit notification
		
		server.exit();
		
	}
	
	/**
	 * Returns whether or not the LSP session is initialized.
	 *
	 * @return Whether or not the LSP session is initialized.
	 */
	boolean initialized() { return initialized; }
	
	/**
	 * Sets the LSP workspace configuration, including project file and
	 * scenario variables, by sending a `workspace/didChangeConfiguration`
	 * notification to the server.
	 * @see AdaLSPDriver#setConfiguration(String)
	 */
	private void setConfiguration() { setConfiguration(null); }
	
	/**
	 * Sets the LSP workspace configuration, including project file and
	 * scenario variables, by sending a `workspace/didChangeConfiguration`
	 * notification to the server. The project file is either set from
	 * the given file path or, in case the path is null, from the GPR file
	 * manager.
	 *
	 * @param gprFilePath The project file path to set, or null in case the
	 *                    path needs to be fetched from the GPR file manager.
	 */
	private void setConfiguration(@Nullable String gprFilePath) {
		
		String path = gprFilePath;
		
		// If no GPR file path was provided, get it
		// from the GPR file manager
		
		if (path == null || "".equals(path)) {
			
			path = gprFileManager.getGprFilePath();
			
			// If no GPR file path is set, return
			
			if ("".equals(path)) { return; }
			
		}
		
		// Get the currently set scenario variables
		
		GPRbuildConfiguration configuration =
			gprbuildConfigurationManager.getSelectedConfiguration();
		
		Map<String, String> scenarioVariables = configuration == null ?
			Collections.emptyMap() : configuration.getScenarioVariables();
		
		// Send the `workspace/didChangeConfiguration`
		// notification to set the project file
		
		server.didChangeConfiguration(path, scenarioVariables);
		
		// Mark the server as initialized
		
		setInitialized();
		
	}
	
	/**
	 * Marks the LSP session as initialized and sets file listeners.
	 */
	private void setInitialized() {
		
		// If the server is already initialized, return
		
		if (initialized) { return; }
		
		// Mark the server as initialized
		
		initialized = true;
		
		// Set file listeners
		
		setFileListeners();
		
	}
	
	/**
	 * Sets file listeners for open/change/close events.
	 */
	private void setFileListeners() {
		
		// Set file open/close listeners
		
		MessageBus messageBus = project.getMessageBus();
		
		FileEditorManagerListener listener = new FileEditorManagerListener() {
			
			/**
			 * @see FileEditorManagerListener#fileOpenedSync(FileEditorManager, VirtualFile, Pair)
			 *
			 * Sends a `textDocument/didOpen` notification to the ALS when a file is opened.
			 */
			@Override
			public void fileOpenedSync(
				@NotNull FileEditorManager source,
				@NotNull VirtualFile file,
				@NotNull Pair<FileEditor[], FileEditorProvider[]> editors
			) {
				
				if (!AdaFileType.isAdaFile(file)) { return; }
				
				Document document = getVirtualFileDocument(file);
				
				if (document != null) {
					Cacher.cacheData(document, AdaLSPClient.DIAGNOSTICS_CACHE_KEY, null);
				}
				
				server.didOpen(file);
				
			}
			
			/**
			 * @see FileEditorManagerListener#fileClosed(FileEditorManager, VirtualFile)
			 *
			 * Sends a `textDocument/didClose` notification to the ALS when a file is closed.
			 */
			@Override
			public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
				
				if (!AdaFileType.isAdaFile(file)) { return; }
				
				server.didClose(file);
				
			}
			
		};
		
		messageBus.connect().subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, listener);
		
		// Set document change listener to clear
		// document diagnostics
		
		EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new AdaDocumentListener() {
			
			/**
			 * @see com.adacore.adaintellij.editor.AdaDocumentListener#beforeAdaDocumentChanged(DocumentEvent)
			 */
			@Override
			public void beforeAdaDocumentChanged(@NotNull DocumentEvent event) {
				Cacher.clearCachedData(event.getDocument(), AdaLSPClient.DIAGNOSTICS_CACHE_KEY);
			}
			
		});
		
		// Initialize document change operation
		
		documentChangeOperation = BusyEditorAwareScheduler.getInstance(project)
			.createDocumentChangeOperation(server::didChange);
		
	}
	
	/**
	 * Prepares LSP initialization parameters, setting all client capabilities,
	 * and returns them.
	 *
	 * @return Initialization parameters for the `initialize` request.
	 */
	@Contract(pure = true)
	private InitializeParams getInitParams() {
		
		// Initialization parameters
		
		InitializeParams params = new InitializeParams();

		// TODO: Find a more reliable way to get process id
		//       Example in Java 9:
		//       int pid = (int)ProcessHandle.current().pid();
		//       Problem: JBRE is based on JDK 8 :(
		int pid = Integer.parseInt(
			java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0]);
		
		params.setProcessId(pid);
		params.setRootUri(project.getBaseDir().getUrl());
		params.setInitializationOptions(null);
		
		params.setCapabilities(new org.eclipse.lsp4j.ClientCapabilities(
			getWorkspaceClientCapabilities(),
			getTextDocumentClientCapabilities(),
			null
		));
		
		return params;
	
	}
	
	/**
	 * Prepares client workspace capability settings, to be used in the parameters
	 * of a client `initialize` request, and returns them.
	 *
	 * @return Workspace capabilities for the `initialize` request.
	 */
	@Contract(pure = true)
	private WorkspaceClientCapabilities getWorkspaceClientCapabilities() {
		
		WorkspaceClientCapabilities workspaceCapabilities = new WorkspaceClientCapabilities();
		
		// WorkspaceEditCapabilities
		
		WorkspaceEditCapabilities workspaceEditCapabilities = new WorkspaceEditCapabilities();
		workspaceEditCapabilities.setDocumentChanges(
			ClientCapabilities.Workspace.WorkspaceEdit.DOCUMENT_CHANGES);
		workspaceEditCapabilities.setResourceOperations(
			ClientCapabilities.Workspace.WorkspaceEdit.RESOURCE_OPERATIONS);
		workspaceEditCapabilities.setFailureHandling(
			ClientCapabilities.Workspace.WorkspaceEdit.FAILURE_HANDLING);
		
		// DidChangeConfigurationCapabilities
		
		DidChangeConfigurationCapabilities didChangeConfigurationCapabilities =
			new DidChangeConfigurationCapabilities(
				ClientCapabilities.Workspace.DidChangeConfiguration.DYNAMIC_REGISTRATION);
		
		// DidChangeWatchedFilesCapabilities
		
		DidChangeWatchedFilesCapabilities didChangeWatchedFilesCapabilities =
			new DidChangeWatchedFilesCapabilities(
				ClientCapabilities.Workspace.DidChangeWatchedFiles.DYNAMIC_REGISTRATION);
		
		// SymbolCapabilities
		
		SymbolKindCapabilities symbolKindCapabilities =
			new SymbolKindCapabilities(ClientCapabilities.Workspace.Symbol.SymbolKind.VALUE_SET);
		
		SymbolCapabilities symbolCapabilities = new SymbolCapabilities(
			symbolKindCapabilities,
			ClientCapabilities.Workspace.Symbol.DYNAMIC_REGISTRATION
		);
		
		// ExecuteCommandCapabilities
		
		ExecuteCommandCapabilities executeCommandCapabilities =
			new ExecuteCommandCapabilities(
				ClientCapabilities.Workspace.ExecuteCommand.DYNAMIC_REGISTRATION);
		
		// Set the capabilities
		
		workspaceCapabilities.setApplyEdit(ClientCapabilities.Workspace.APPLY_EDIT);
		workspaceCapabilities.setWorkspaceEdit(workspaceEditCapabilities);
		workspaceCapabilities.setDidChangeConfiguration(didChangeConfigurationCapabilities);
		workspaceCapabilities.setDidChangeWatchedFiles(didChangeWatchedFilesCapabilities);
		workspaceCapabilities.setSymbol(symbolCapabilities);
		workspaceCapabilities.setExecuteCommand(executeCommandCapabilities);
		workspaceCapabilities.setWorkspaceFolders(ClientCapabilities.Workspace.WORKSPACE_FOLDERS);
		workspaceCapabilities.setConfiguration(ClientCapabilities.Workspace.CONFIGURATION);
		
		// Return the capabilities
		
		return workspaceCapabilities;
		
	}
	
	/**
	 * Prepares client text document capability settings, to be used in the parameters
	 * of a client `initialize` request, and returns them.
	 *
	 * @return Text document capabilities for the `initialize` request.
	 */
	@Contract(pure = true)
	private static TextDocumentClientCapabilities getTextDocumentClientCapabilities() {
		
		TextDocumentClientCapabilities textDocumentCapabilities = new TextDocumentClientCapabilities();
		
		// SynchronizationCapabilities
		
		SynchronizationCapabilities synchronizationCapabilities =
			new SynchronizationCapabilities(
				ClientCapabilities.TextDocument.Synchronization.WILL_SAVE,
				ClientCapabilities.TextDocument.Synchronization.WILL_SAVE_WAIT_UNTIL,
				ClientCapabilities.TextDocument.Synchronization.DID_SAVE,
				ClientCapabilities.TextDocument.Synchronization.DYNAMIC_REGISTRATION
			);
		
		// CompletionItemCapabilities
		
		CompletionItemCapabilities completionItemCapabilities = new CompletionItemCapabilities();
		completionItemCapabilities.setSnippetSupport(
			ClientCapabilities.TextDocument.Completion.CompletionItem.SNIPPET_SUPPORT);
		completionItemCapabilities.setCommitCharactersSupport(
			ClientCapabilities.TextDocument.Completion.CompletionItem.COMMIT_CHARACTERS_SUPPORT);
		completionItemCapabilities.setDocumentationFormat(
			ClientCapabilities.TextDocument.Completion.CompletionItem.DOCUMENTATION_FORMAT);
		completionItemCapabilities.setDeprecatedSupport(
			ClientCapabilities.TextDocument.Completion.CompletionItem.DEPRECATED_SUPPORT);
		completionItemCapabilities.setPreselectSupport(
			ClientCapabilities.TextDocument.Completion.CompletionItem.PRESELECT_SUPPORT);
		
		CompletionItemKindCapabilities completionItemKindCapabilities =
			new CompletionItemKindCapabilities();
		completionItemKindCapabilities.setValueSet(
			ClientCapabilities.TextDocument.Completion.CompletionItemKind.VALUE_SET);
		
		CompletionCapabilities completionCapabilities = new CompletionCapabilities();
		completionCapabilities.setDynamicRegistration(
			ClientCapabilities.TextDocument.Completion.DYNAMIC_REGISTRATION);
		completionCapabilities.setCompletionItem(completionItemCapabilities);
		completionCapabilities.setCompletionItemKind(completionItemKindCapabilities);
		completionCapabilities.setContextSupport(
			ClientCapabilities.TextDocument.Completion.CONTEXT_SUPPORT);
		
		// HoverCapabilities
		
		HoverCapabilities hoverCapabilities = new HoverCapabilities(
			ClientCapabilities.TextDocument.Hover.CONTENT_FORMAT,
			ClientCapabilities.TextDocument.Hover.DYNAMIC_REGISTRATION
		);
		
		// SignatureHelpCapabilities
		
		SignatureHelpCapabilities signatureHelpCapabilities =
			new SignatureHelpCapabilities(
				new SignatureInformationCapabilities(
					ClientCapabilities.TextDocument.SignatureHelp.SignatureInformation.DOCUMENTATION_FORMAT
				),
				ClientCapabilities.TextDocument.SignatureHelp.DYNAMIC_REGISTRATION
			);
		
		// ReferencesCapabilities
		
		ReferencesCapabilities referencesCapabilities = new ReferencesCapabilities(
			ClientCapabilities.TextDocument.References.DYNAMIC_REGISTRATION);
		
		// DocumentHighlightCapabilities
		
		DocumentHighlightCapabilities documentHighlightCapabilities =
			new DocumentHighlightCapabilities(
				ClientCapabilities.TextDocument.DocumentHighlight.DYNAMIC_REGISTRATION);
		
		// DocumentSymbolCapabilities
		
		SymbolKindCapabilities symbolKindCapabilities = new SymbolKindCapabilities(
			ClientCapabilities.TextDocument.DocumentSymbol.SymbolKind.VALUE_SET);
		
		DocumentSymbolCapabilities documentSymbolCapabilities = new DocumentSymbolCapabilities();
		documentSymbolCapabilities.setDynamicRegistration(
			ClientCapabilities.TextDocument.DocumentSymbol.DYNAMIC_REGISTRATION);
		documentSymbolCapabilities.setSymbolKind(symbolKindCapabilities);
		documentSymbolCapabilities.setHierarchicalDocumentSymbolSupport(
			ClientCapabilities.TextDocument.DocumentSymbol.HIERARCHICAL_DOCUMENT_SYMBOL_SUPPORT);
		
		// FormattingCapabilities
		
		FormattingCapabilities formattingCapabilities = new FormattingCapabilities(
			ClientCapabilities.TextDocument.Formatting.DYNAMIC_REGISTRATION);
		
		// RangeFormattingCapabilities
		
		RangeFormattingCapabilities rangeFormattingCapabilities = new RangeFormattingCapabilities(
			ClientCapabilities.TextDocument.RangeFormatting.DYNAMIC_REGISTRATION);
		
		// OnTypeFormattingCapabilities
		
		OnTypeFormattingCapabilities onTypeFormattingCapabilities = new OnTypeFormattingCapabilities(
			ClientCapabilities.TextDocument.OnTypeFormatting.DYNAMIC_REGISTRATION);
		
		// DefinitionCapabilities
		
		DefinitionCapabilities definitionCapabilities = new DefinitionCapabilities(
			ClientCapabilities.TextDocument.Definition.DYNAMIC_REGISTRATION);
		
		// TypeDefinitionCapabilities
		
		TypeDefinitionCapabilities typeDefinitionCapabilities = new TypeDefinitionCapabilities(
			ClientCapabilities.TextDocument.TypeDefinition.DYNAMIC_REGISTRATION);
		
		// ImplementationCapabilities
		
		ImplementationCapabilities implementationCapabilities = new ImplementationCapabilities(
			ClientCapabilities.TextDocument.Implementation.DYNAMIC_REGISTRATION);
		
		// CodeActionCapabilities
		
		CodeActionLiteralSupportCapabilities codeActionLiteralSupportCapabilities =
			new CodeActionLiteralSupportCapabilities(new CodeActionKindCapabilities(
				ClientCapabilities.TextDocument.CodeAction.CodeActionLiteralSupport.CodeActionKind.VALUE_SET
			));
		
		CodeActionCapabilities codeActionCapabilities = new CodeActionCapabilities(
			codeActionLiteralSupportCapabilities,
			ClientCapabilities.TextDocument.CodeAction.DYNAMIC_REGISTRATION
		);
		
		// CodeLensCapabilities
		
		CodeLensCapabilities codeLensCapabilities = new CodeLensCapabilities(
			ClientCapabilities.TextDocument.CodeLens.DYNAMIC_REGISTRATION);
		
		// DocumentLinkCapabilities
		
		DocumentLinkCapabilities documentLinkCapabilities = new DocumentLinkCapabilities(
			ClientCapabilities.TextDocument.DocumentLink.DYNAMIC_REGISTRATION);
		
		// ColorProviderCapabilities
		
		ColorProviderCapabilities colorProviderCapabilities = new ColorProviderCapabilities(
			ClientCapabilities.TextDocument.ColorProvider.DYNAMIC_REGISTRATION);
		
		// RenameCapabilities
		
		RenameCapabilities renameCapabilities = new RenameCapabilities();
		renameCapabilities.setDynamicRegistration(
			ClientCapabilities.TextDocument.Rename.DYNAMIC_REGISTRATION);
		renameCapabilities.setPrepareSupport(
			ClientCapabilities.TextDocument.Rename.PREPARE_SUPPORT);
		
		// PublishDiagnosticsCapabilities
		
		PublishDiagnosticsCapabilities publishDiagnosticsCapabilities =
			new PublishDiagnosticsCapabilities(
				ClientCapabilities.TextDocument.PublishDiagnostics.RELATED_INFORMATION);
		
		// FoldingRangeCapabilities
		
		FoldingRangeCapabilities foldingRangeCapabilities = new FoldingRangeCapabilities();
		foldingRangeCapabilities.setDynamicRegistration(
			ClientCapabilities.TextDocument.FoldingRange.DYNAMIC_REGISTRATION);
		foldingRangeCapabilities.setRangeLimit(
			ClientCapabilities.TextDocument.FoldingRange.RANGE_LIMIT);
		foldingRangeCapabilities.setLineFoldingOnly(
			ClientCapabilities.TextDocument.FoldingRange.LINE_FOLDING_ONLY);
		
		// Set the capabilities
		
		textDocumentCapabilities.setSynchronization(synchronizationCapabilities);
		textDocumentCapabilities.setCompletion(completionCapabilities);
		textDocumentCapabilities.setHover(hoverCapabilities);
		textDocumentCapabilities.setSignatureHelp(signatureHelpCapabilities);
		textDocumentCapabilities.setReferences(referencesCapabilities);
		textDocumentCapabilities.setDocumentHighlight(documentHighlightCapabilities);
		textDocumentCapabilities.setDocumentSymbol(documentSymbolCapabilities);
		textDocumentCapabilities.setFormatting(formattingCapabilities);
		textDocumentCapabilities.setRangeFormatting(rangeFormattingCapabilities);
		textDocumentCapabilities.setOnTypeFormatting(onTypeFormattingCapabilities);
		textDocumentCapabilities.setDefinition(definitionCapabilities);
		textDocumentCapabilities.setTypeDefinition(typeDefinitionCapabilities);
		textDocumentCapabilities.setImplementation(implementationCapabilities);
		textDocumentCapabilities.setCodeAction(codeActionCapabilities);
		textDocumentCapabilities.setCodeLens(codeLensCapabilities);
		textDocumentCapabilities.setDocumentLink(documentLinkCapabilities);
		textDocumentCapabilities.setColorProvider(colorProviderCapabilities);
		textDocumentCapabilities.setRename(renameCapabilities);
		textDocumentCapabilities.setPublishDiagnostics(publishDiagnosticsCapabilities);
		textDocumentCapabilities.setFoldingRange(foldingRangeCapabilities);
		
		// Return the capabilities
		
		return textDocumentCapabilities;
		
	}
	
}
