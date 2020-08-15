package com.adacore.adaintellij.analysis.semantic.completion;

import com.adacore.adaintellij.lsp.AdaLSPDriver;
import com.adacore.adaintellij.project.AdaProject;
import com.adacore.adaintellij.project.GPRFileManager;
import com.intellij.codeInsight.lookup.impl.LookupImpl;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.CompletionAutoPopupTester;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import junit.framework.Assert;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

public class AdaCompletionContributorTest extends LightJavaCodeInsightFixtureTestCase {

    @Override
    protected String getTestDataPath() {
        return "src/test/resources/ada-sources";
    }

    @BeforeEach
    public void  setup() throws Exception
    {
        super.setUp();
    }

    //@Test
    public void noEmptyPrefix() {

        //VirtualFile file = myFixture.copyFileToProject( "/empty.ads");
//        myFixture.copyFileToProject( "/project.gpr");
//
//        myFixture.getProject().getComponent(AdaProject.class).projectOpened();
//        myFixture.getProject().getComponent(AdaLSPDriver.class).projectOpened();
//        myFixture.getProject().getComponent(GPRFileManager.class).projectOpened();
//
//        myFixture.configureByFile("empty.ads");
//
//        CompletionAutoPopupTester tester = new CompletionAutoPopupTester(myFixture);
//        tester.runWithAutoPopupEnabled(() -> {
//
//            tester.typeWithPauses("ob");
//            tester.joinCompletion();
//            LookupImpl lookup = tester.getLookup();
//            Integer one = 1;
//        });

//        Assert.assertEquals("expected no completions for an empty file",
//            0, myFixture.completeBasic().length);
//
//        // whitespace suffix
//        myFixture.configureByText("main.ads", "");
//        myFixture.type(" ");
//        Assert.assertEquals("expected no completions after whitespace",
//            0, myFixture.completeBasic().length);


    }

    @NotNull
    @Override
    protected LightProjectDescriptor getProjectDescriptor() {
        return new LightProjectDescriptor(){
            protected VirtualFile createSourceRoot(@NotNull Module module, String srcPath) {

                String  fp = myFixture.getTempDirFixture()
                    .getTempDirPath();

                VirtualFile srcRootDirVF = VirtualFileManager
                    .getInstance()
                    .refreshAndFindFileByUrl(
                        "file://" + myFixture.getTempDirFixture()
                            .getTempDirPath()
                    );

                assert srcRootDirVF != null;
                srcRootDirVF.refresh( false, false );
                VirtualFile srcRootVF = null;
                try {
                    srcRootVF = srcRootDirVF.createChildDirectory(this, srcPath);
                }catch (IOException e) {
                    throw new RuntimeException(e);
                }

                registerSourceRoot( module.getProject(), srcRootVF);
                return srcRootVF;
            }
        };
    }
}
