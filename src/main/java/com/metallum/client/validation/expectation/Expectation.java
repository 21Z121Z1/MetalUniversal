package com.metallum.client.validation.expectation;

import com.metallum.client.validation.capture.CapturedResource;

/** A machine-checkable contract for one captured resource. */
public interface Expectation {
    ExpectationResult evaluate(CapturedResource actual, ExpectationContext context);

    /** Optional reference bytes used by the artifact writer. */
    default byte[] expectedBytes() {
        return null;
    }
}
