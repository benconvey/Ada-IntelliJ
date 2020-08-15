package com.adacore.adaintellij.misc;

import com.adacore.adaintellij.lsp.AdaLSPDriver;
import com.adacore.adaintellij.project.AdaProject;
import com.adacore.adaintellij.project.GPRFileManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.*;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

public class AdaFoldingBuilderTest extends LightJavaCodeInsightFixtureTestCase {

    Module myModule;

    @Override
    protected String getTestDataPath() {
        return "src/test/resources/ada-sources";
    }

    @BeforeEach
    public void  setup() throws Exception
    {
        super.setUp();
    }

    @Test
    public void testFolds()
    {
        myFixture.copyFileToProject( "/project.gpr");

        myFixture.getProject().getComponent(AdaProject.class).projectOpened();
        myFixture.getProject().getComponent(AdaLSPDriver.class).projectOpened();
        myFixture.getProject().getComponent(GPRFileManager.class).projectOpened();

        myFixture.testFoldingWithCollapseStatus(getTestDataPath() + "/folding-test-data.ads");
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
