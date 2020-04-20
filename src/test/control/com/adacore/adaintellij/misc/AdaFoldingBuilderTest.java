package com.adacore.adaintellij.misc;

import com.intellij.testFramework.TestDataPath;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * JUnit test class for the AdaFoldingBuilder class.
 */
final public class AdaFoldingBuilderTest extends BasePlatformTestCase {

    @BeforeEach
    protected void setUp() throws Exception {
        super.setUp();
    }

    @Override
    protected String getTestDataPath() {
        return "src/test/resources/AdaFoldingBuilder/";
    }

    @Test
    void testFolding(){
        myFixture.configureByFile("main_before.ads");
        myFixture.testFolding(getTestDataPath() + "main_folded.ads");
    }

}
