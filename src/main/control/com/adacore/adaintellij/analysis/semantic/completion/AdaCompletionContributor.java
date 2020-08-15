package com.adacore.adaintellij.analysis.semantic.completion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

import org.eclipse.lsp4j.CompletionItem;

import com.adacore.adaintellij.lsp.AdaLSPDriver;
import com.adacore.adaintellij.lsp.AdaLSPServer;

import static com.adacore.adaintellij.Utils.getPsiFileDocument;
import static com.adacore.adaintellij.lsp.LSPUtils.offsetToPosition;

/**
 * Completion contributor for Ada, powered by the
 * Ada Language Server (ALS).
 */
public final class AdaCompletionContributor extends CompletionContributor {

	private ArrayList<String> keywords = new ArrayList<>(
		Arrays.asList("abort","abs", "abstract","accept", "access", "aliased", "all", "and", "array", "at",
			"begin", "body", "case", "constant", "declare", "delay", "delta", "digits", "do", "else", "elsif", "end",
			"entry", "exception", "exit", "for", "function", "generic", "goto", "if", "in", "interface",
			"is", "limited", "loop", "mod", "new", "not", "null", "of", "or", "others", "out", "overriding", "package",
			"pragma", "private", "procedure", "protected", "raise", "range", "record", "rem", "renames", "requeue",
			"return", "reverse", "select", "separate", "some", "subtype", "synchronized", "tagged", "task",
			"terminate", "then", "type", "until", "use", "when", "while", "with", "xor"
		));

	/**
	 * @see CompletionContributor#fillCompletionVariants(CompletionParameters, CompletionResultSet)
	 *
	 * Makes a `textDocument/completion` request to the ALS to get a
	 * list of completion items for the current caret position, and
	 * adds them to the given completion results.
	 */
	@Override
	public void fillCompletionVariants(
		@NotNull CompletionParameters parameters,
		@NotNull CompletionResultSet  result
	) {

		PsiFile  file        = parameters.getOriginalFile();
		Document document    = getPsiFileDocument(file);
		String   documentUri = file.getVirtualFile().getUrl();

		if (document == null) { return; }

		Project project = parameters.getOriginalFile().getProject();

		// Make the request and wait for the result

		AdaLSPServer lspServer = AdaLSPDriver.getServer(project);

		if (lspServer == null) { return; }

		List<CompletionItem> completionItems =
			lspServer.completion(documentUri, offsetToPosition(document, parameters.getOffset()));

		// Map completion items to instances of `LookupElement`
		// and add them all to the given `CompletionResult`

		result.addAllElements(
			completionItems
				.stream()
				.map(completionItem -> {

					LookupElementBuilder element =
						LookupElementBuilder.create(completionItem.getLabel())
							.withCaseSensitivity(false)
							.withInsertHandler(new InsertHandler<LookupElement>() {
								@Override
								public void handleInsert(@NotNull InsertionContext context, @NotNull LookupElement item) {

									String insertString = context.getDocument().getText(
										new TextRange(
											context.getStartOffset(),
											context.getTailOffset()
										)
									);

									if (keywords.contains(insertString.toLowerCase())) {
										context.getDocument().replaceString(
											context.getStartOffset(),
											context.getTailOffset(),
											insertString.toLowerCase()
										);
									}
								}
							})
							.bold();

					Boolean deprecated = completionItem.getDeprecated();

					if (deprecated != null && deprecated) {
						element = element.strikeout();
					}

					String detail = completionItem.getDetail();

					if (detail != null) {
						element = element.withTypeText(detail, null, false);
					}

					return element;

				})
				.collect(Collectors.toList())
		);

	}

}
