package com.adacore.adaintellij.misc;

import com.adacore.adaintellij.Utils;
import com.adacore.adaintellij.analysis.syntactic.AdaPsiElement;
import com.adacore.adaintellij.lsp.AdaLSPDriver;
import com.adacore.adaintellij.lsp.AdaLSPServer;
import com.intellij.coverage.SimpleCoverageAnnotator;
import com.intellij.lang.ASTNode;
import com.intellij.lang.folding.FoldingBuilderEx;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.FoldingGroup;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.FoldingRange;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import com.intellij.openapi.diagnostic.Logger;

import java.util.*;

public class AdaFoldingBuilder extends FoldingBuilderEx implements DumbAware {

    Project project;
    PsiFile psiFile;
    AdaLSPServer lspServer;
    Document document;
    List<FoldingRange> previousFoldingRanges;
    PsiElement root;

    @NotNull
    @Override
    public FoldingDescriptor[] buildFoldRegions(@NotNull PsiElement root, @NotNull Document document, boolean quick) {
        this.project = root.getProject();
        this.psiFile = root.getContainingFile();
        this.lspServer = AdaLSPDriver.getServer(project);
        this.document = document;
        this.root = root;

        AdaLSPDriver driver = AdaLSPDriver.getInstance(this.project);

        synchronized (driver.getDocumentChangeOperation()) {


            Logger logger = Logger.getInstance(Utils.class);

            try {

                if(driver.getDocumentChangeOperation().getQueue().isFlushing() || ! driver.getDocumentChangeOperation().getQueue().isEmpty() ){

                    logger.warn("Waiting");
                    driver.getDocumentChangeOperation().wait();
                }
                logger.warn("Finished Waiting");
                List<FoldingRange> foldingRanges = lspServer.foldingRange(psiFile.getVirtualFile().getUrl());

                return buildListOfFoldingDescriptors(foldingRanges);
            } catch (InterruptedException e ){
                logger.warn("Interrupted Exception");
                return new FoldingDescriptor[0];
            }
        }
    }

    private boolean foldingRangesHaveChanged(List<FoldingRange> foldingRanges){
        if (previousFoldingRanges == null) return true;

        return ! foldingRanges.stream().allMatch((FoldingRange range) -> {
            return previousFoldingRanges.contains(range);
        });
    }

    private FoldingDescriptor[] buildListOfFoldingDescriptors(List<FoldingRange> foldingRanges){
        List<FoldingDescriptor> descriptors = new ArrayList<>();

        for(FoldingRange foldingRange :  foldingRanges) {
            int foldStartOffset;
            int foldEndOffset;

            try {
                foldStartOffset = document.getLineStartOffset(foldingRange.getStartLine());
                foldEndOffset = document.getLineEndOffset(foldingRange.getEndLine());
            } catch (IndexOutOfBoundsException e) {
                continue;
            }

            if (foldStartOffset < foldEndOffset) {

                Set<Object> dependencies = new HashSet<>();

                descriptors.add(new FoldingDescriptor(
                        root.getNode(),
                        new TextRange(
                                foldStartOffset,
                                foldEndOffset
                        ),
                        null,
                        document.getText(new TextRange(
                                foldStartOffset,
                                document.getLineEndOffset(foldingRange.getStartLine())
                        )) + " ...",
                        foldingRange.getKind().equals("imports"),
                        dependencies
                ));
            }
        }

        previousFoldingRanges = foldingRanges;

        return descriptors.toArray(new FoldingDescriptor[descriptors.size()]);
    }

    @Nullable
    @Override
    public String getPlaceholderText(@NotNull ASTNode node) {
        return null;
    }

    @Override
    public boolean isCollapsedByDefault(@NotNull ASTNode node) {
        return false;
    }
}
