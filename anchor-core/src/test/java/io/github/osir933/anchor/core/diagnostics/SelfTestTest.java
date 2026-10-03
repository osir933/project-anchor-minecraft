package io.github.osir933.anchor.core.diagnostics;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SelfTestTest {

    @Test
    void theSelfTestPasses() {
        SelfTest.Report report = SelfTest.run();
        assertTrue(report.passed(), () -> String.join("\n", report.lines()));
        assertTrue(report.lines().get(0).startsWith("Anchor self-test passed"));
    }
}
