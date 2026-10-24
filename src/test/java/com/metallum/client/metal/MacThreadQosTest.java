package com.metallum.client.metal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacThreadQosTest {
    @AfterEach
    void clear() {
        for (String role : new String[]{"render", "server", "mesh", "cull", "worker"}) {
            System.clearProperty("metallum.opt.qos." + role);
        }
    }

    @Test
    void classesAreExplicitAndFailClosed() {
        assertFalse(MacThreadQos.configured("render"));
        System.setProperty("metallum.opt.qos.render", "interactive");
        assertTrue(MacThreadQos.configured("render"));
        assertEquals(0x21, MacThreadQos.qosClass("render"));
        System.setProperty("metallum.opt.qos.render", "initiated");
        assertEquals(0x19, MacThreadQos.qosClass("render"));
        System.setProperty("metallum.opt.qos.render", "bogus");
        assertEquals(0, MacThreadQos.qosClass("render"));
    }
}
