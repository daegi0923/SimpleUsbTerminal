package de.kai_morich.simple_usb_terminal;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Chooses a USB port without depending on Android device APIs. */
public final class UsbAutoConnectPolicy {
    public static final long RETRY_DELAY_MILLIS = 2000;

    public enum Kind { NONE, REQUEST_PERMISSION, CONNECT }

    public static final class Candidate {
        public final int deviceId;
        public final int vendorId;
        public final int productId;
        public final int port;
        public final boolean hasPermission;

        public Candidate(int deviceId, int vendorId, int productId, int port, boolean hasPermission) {
            this.deviceId = deviceId;
            this.vendorId = vendorId;
            this.productId = productId;
            this.port = port;
            this.hasPermission = hasPermission;
        }
    }

    public static final class Action {
        public final Kind kind;
        public final Candidate candidate;

        private Action(Kind kind, Candidate candidate) {
            this.kind = kind;
            this.candidate = candidate;
        }
    }

    private static final Action NONE = new Action(Kind.NONE, null);
    private final Set<Integer> deniedDevices = new HashSet<>();
    private boolean enabled = true;
    private boolean hasPreference;
    private int preferredVendorId;
    private int preferredProductId;
    private int preferredPort;
    private Integer preferredAttachedDevice;
    private long retryAtMillis;
    private Action pending = NONE;
    private Candidate connected;

    /** Use a monotonic clock such as SystemClock.elapsedRealtime(). */
    public Action scan(List<Candidate> candidates, long nowMillis) {
        Iterator<Integer> denied = deniedDevices.iterator();
        while (denied.hasNext()) {
            int deviceId = denied.next();
            boolean attached = false;
            for (Candidate candidate : candidates) {
                if (candidate.deviceId == deviceId) {
                    attached = true;
                    break;
                }
            }
            if (!attached) denied.remove();
        }
        if (preferredAttachedDevice != null) {
            boolean attached = false;
            for (Candidate candidate : candidates) {
                if (candidate.deviceId == preferredAttachedDevice) {
                    attached = true;
                    break;
                }
            }
            if (!attached) preferredAttachedDevice = null;
        }
        if (pending.candidate != null && !contains(candidates, pending.candidate)) pending = NONE;
        if (connected != null && !contains(candidates, connected)) connected = null;
        if (!enabled || pending != NONE || connected != null || nowMillis < retryAtMillis) return NONE;

        Candidate selected = null;
        for (Candidate candidate : candidates) {
            if (preferredAttachedDevice != null && candidate.deviceId != preferredAttachedDevice) continue;
            if (hasPreference && (candidate.vendorId != preferredVendorId
                    || candidate.productId != preferredProductId || candidate.port != preferredPort)) continue;
            // Two matching physical devices are ambiguous, even if their VID/PID are identical.
            if (selected != null) return NONE;
            selected = candidate;
        }
        if (selected == null || deniedDevices.contains(selected.deviceId)) return NONE;
        pending = new Action(selected.hasPermission ? Kind.CONNECT : Kind.REQUEST_PERMISSION, selected);
        return pending;
    }

    public void setPreferred(int vendorId, int productId, int port) {
        hasPreference = true;
        preferredAttachedDevice = null;
        preferredVendorId = vendorId;
        preferredProductId = productId;
        preferredPort = port;
    }

    /** Preserve a manual choice among identical adapters until that device detaches. */
    public void setPreferredAttachedDevice(int deviceId) {
        preferredAttachedDevice = deviceId;
    }

    public void clearPreferred() {
        hasPreference = false;
        preferredAttachedDevice = null;
    }

    /** Handle brief unplug/replug events that can happen between polling scans. */
    public void onDetached(int deviceId) {
        deniedDevices.remove(deviceId);
        if (pending.candidate != null && pending.candidate.deviceId == deviceId) pending = NONE;
        if (connected != null && connected.deviceId == deviceId) connected = null;
        if (preferredAttachedDevice != null && preferredAttachedDevice == deviceId) preferredAttachedDevice = null;
    }

    public void onPermissionResult(int deviceId, boolean granted) {
        if (pending.kind != Kind.REQUEST_PERMISSION || pending.candidate.deviceId != deviceId) return;
        if (!granted) deniedDevices.add(deviceId);
        pending = NONE;
    }

    public void onConnected() {
        if (pending.kind != Kind.CONNECT) return;
        connected = pending.candidate;
        pending = NONE;
    }

    public void onConnectionFailed(long nowMillis) {
        connected = null;
        pending = NONE;
        retryAtMillis = nowMillis + RETRY_DELAY_MILLIS;
    }

    public void stop() {
        enabled = false;
        connected = null;
        pending = NONE;
    }

    /** An explicit user retry also permits asking for denied USB access again. */
    public void resume() {
        enabled = true;
        deniedDevices.clear();
        retryAtMillis = 0;
    }

    private static boolean contains(List<Candidate> candidates, Candidate target) {
        for (Candidate candidate : candidates) {
            if (candidate.deviceId == target.deviceId && candidate.port == target.port) return true;
        }
        return false;
    }
}
