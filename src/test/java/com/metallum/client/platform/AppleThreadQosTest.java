package com.metallum.client.platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppleThreadQosTest {
    @AfterEach
    void clear() {
        for (String role : new String[]{"render","server","mesh","cull","worker"}) {
            System.clearProperty("metallum.opt.qos." + role);
        }
    }

    @Test
    void parsesOnlyKnownQosClasses() {
        assertEquals(0, AppleThreadQos.level("render"));
        System.setProperty("metallum.opt.qos.render", "interactive");
        assertEquals(0x21, AppleThreadQos.level("render"));
        System.setProperty("metallum.opt.qos.render", "initiated");
        assertEquals(0x19, AppleThreadQos.level("render"));
        System.setProperty("metallum.opt.qos.render", "utility");
        assertEquals(0x11, AppleThreadQos.level("render"));
        System.setProperty("metallum.opt.qos.render", "unknown");
        assertEquals(0, AppleThreadQos.level("render"));
    }
}
