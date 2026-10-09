package com.google.android.datatransport.cct;

import com.google.android.datatransport.Encoding;
import com.google.android.datatransport.runtime.EncodedDestination;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Privacy stub. Shuddh deliberately excludes Google's CCT telemetry uploader
 * (transport-backend-cct), but MediaPipe Tasks and ML Kit still reference this destination when
 * they start their usage loggers. With this stub the loggers initialise, find no registered "cct"
 * backend, and their events are discarded on the device — nothing is ever uploaded.
 */
public final class CCTDestination implements EncodedDestination {
    public static final CCTDestination INSTANCE = new CCTDestination();
    public static final CCTDestination LEGACY_INSTANCE = INSTANCE;

    private static final Set<Encoding> ENCODINGS;
    static {
        Set<Encoding> s = new HashSet<>();
        s.add(Encoding.of("proto"));
        s.add(Encoding.of("json"));
        ENCODINGS = Collections.unmodifiableSet(s);
    }

    private CCTDestination() {}

    @Override public String getName() { return "cct"; }

    @Override public byte[] getExtras() { return null; }

    @Override public Set<Encoding> getSupportedEncodings() { return ENCODINGS; }
}
