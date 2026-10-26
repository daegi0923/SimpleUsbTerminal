package de.kai_morich.simple_usb_terminal;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Exercises automatic connection decisions without USB hardware. */
public final class UsbAutoConnectPolicyTest {
    public static void main(String[] args) {
        selectsOnlyUnambiguousPorts();
        remembersManualChoiceAmongIdenticalAdapters();
        doesNotDuplicatePendingActions();
        retriesFailedConnectionsAfterTwoSeconds();
        blocksDeniedPermissionUntilDetachOrExplicitRetry();
        stopsUntilExplicitResume();
        forgetsDetachedConnectionsAndPermissionRequests();
        handlesDetachEventsBetweenScans();
        System.out.println("PASS: USB selection, identical-adapter manual choice, pending actions, 2000ms retry, permission denial, stop/resume, detach");
    }

    private static void selectsOnlyUnambiguousPorts() {
        UsbAutoConnectPolicy policy = new UsbAutoConnectPolicy();
        expect(policy.scan(Collections.emptyList(), 0), UsbAutoConnectPolicy.Kind.NONE, "no devices");
        expect(policy.scan(one(1, true), 0), UsbAutoConnectPolicy.Kind.CONNECT, "one port should connect");

        UsbAutoConnectPolicy multiple = new UsbAutoConnectPolicy();
        List<UsbAutoConnectPolicy.Candidate> ports = Arrays.asList(
                candidate(1, 10, 20, 0, true), candidate(2, 30, 40, 0, true));
        expect(multiple.scan(ports, 0), UsbAutoConnectPolicy.Kind.NONE, "ambiguous devices");
        multiple.setPreferred(30, 40, 0);
        UsbAutoConnectPolicy.Action selected = multiple.scan(ports, 0);
        expect(selected, UsbAutoConnectPolicy.Kind.CONNECT, "stored device preference");
        check(selected.candidate.deviceId == 2, "must select the preferred device");

        UsbAutoConnectPolicy duplicateIdentity = new UsbAutoConnectPolicy();
        duplicateIdentity.setPreferred(10, 20, 0);
        expect(duplicateIdentity.scan(Arrays.asList(
                candidate(1, 10, 20, 0, true), candidate(2, 10, 20, 0, true)), 0),
                UsbAutoConnectPolicy.Kind.NONE, "identical USB adapters remain ambiguous");
        expect(duplicateIdentity.scan(Collections.singletonList(candidate(3, 30, 40, 0, true)), 0),
                UsbAutoConnectPolicy.Kind.NONE, "do not substitute another device for preferred device");

        UsbAutoConnectPolicy multiPort = new UsbAutoConnectPolicy();
        List<UsbAutoConnectPolicy.Candidate> sameDevice = Arrays.asList(
                candidate(1, 10, 20, 0, true), candidate(1, 10, 20, 1, true));
        expect(multiPort.scan(sameDevice, 0), UsbAutoConnectPolicy.Kind.NONE, "ambiguous serial ports");
        multiPort.setPreferred(10, 20, 1);
        check(multiPort.scan(sameDevice, 0).candidate.port == 1, "stored port preference");
    }

    private static void remembersManualChoiceAmongIdenticalAdapters() {
        UsbAutoConnectPolicy policy = new UsbAutoConnectPolicy();
        List<UsbAutoConnectPolicy.Candidate> waiting = Arrays.asList(
                candidate(1, 10, 20, 0, false), candidate(2, 10, 20, 0, false));
        List<UsbAutoConnectPolicy.Candidate> permitted = Arrays.asList(
                candidate(1, 10, 20, 0, false), candidate(2, 10, 20, 0, true));
        policy.setPreferred(10, 20, 0);
        policy.setPreferredAttachedDevice(2);
        UsbAutoConnectPolicy.Action request = policy.scan(waiting, 0);
        expect(request, UsbAutoConnectPolicy.Kind.REQUEST_PERMISSION, "manual choice resolves identical adapters");
        check(request.candidate.deviceId == 2, "permission targets chosen adapter");
        expect(policy.scan(waiting, 1), UsbAutoConnectPolicy.Kind.NONE, "manual permission request is not duplicated");
        policy.onPermissionResult(2, true);
        UsbAutoConnectPolicy.Action connect = policy.scan(permitted, 2);
        expect(connect, UsbAutoConnectPolicy.Kind.CONNECT, "retain manual choice after permission grant");
        check(connect.candidate.deviceId == 2, "open targets chosen adapter");
        policy.onConnected();
        policy.onConnectionFailed(3);
        expect(policy.scan(permitted, 2002), UsbAutoConnectPolicy.Kind.NONE, "manual choice obeys retry delay");
        UsbAutoConnectPolicy.Action retry = policy.scan(permitted, 2003);
        expect(retry, UsbAutoConnectPolicy.Kind.CONNECT, "retain manual choice after IO failure");
        check(retry.candidate.deviceId == 2, "retry targets chosen adapter");
        policy.onConnected();
        policy.stop();
        policy.resume();
        check(policy.scan(permitted, 2004).candidate.deviceId == 2, "explicit retry retains attached adapter choice");
        policy.scan(Collections.emptyList(), 2005);
        expect(policy.scan(permitted, 2006), UsbAutoConnectPolicy.Kind.NONE, "detach clears transient choice and restores ambiguity");

        policy.setPreferredAttachedDevice(2);
        policy.setPreferred(10, 20, 0);
        expect(policy.scan(permitted, 2007), UsbAutoConnectPolicy.Kind.NONE, "new stored preference clears transient choice");
    }

    private static void doesNotDuplicatePendingActions() {
        UsbAutoConnectPolicy policy = new UsbAutoConnectPolicy();
        expect(policy.scan(one(1, false), 0), UsbAutoConnectPolicy.Kind.REQUEST_PERMISSION, "request permission");
        expect(policy.scan(one(1, false), 10000), UsbAutoConnectPolicy.Kind.NONE, "one permission dialog per attachment");
        policy.onPermissionResult(999, false);
        expect(policy.scan(one(1, false), 10001), UsbAutoConnectPolicy.Kind.NONE, "ignore unrelated permission result");
        policy.onPermissionResult(1, true);
        expect(policy.scan(one(1, true), 10002), UsbAutoConnectPolicy.Kind.CONNECT, "connect after grant");
        expect(policy.scan(one(1, true), 20000), UsbAutoConnectPolicy.Kind.NONE, "no duplicate open while connecting");
        policy.onConnected();
        expect(policy.scan(one(1, true), 30000), UsbAutoConnectPolicy.Kind.NONE, "no duplicate open while connected");
    }

    private static void retriesFailedConnectionsAfterTwoSeconds() {
        UsbAutoConnectPolicy policy = new UsbAutoConnectPolicy();
        expect(policy.scan(one(1, true), 0), UsbAutoConnectPolicy.Kind.CONNECT, "initial open");
        policy.onConnectionFailed(500);
        expect(policy.scan(one(1, true), 2499), UsbAutoConnectPolicy.Kind.NONE, "wait full retry interval after open failure");
        expect(policy.scan(one(1, true), 2500), UsbAutoConnectPolicy.Kind.CONNECT, "retry at 2000ms boundary");
        policy.onConnected();
        policy.onConnectionFailed(3000);
        expect(policy.scan(one(1, true), 4999), UsbAutoConnectPolicy.Kind.NONE, "wait after established connection fails");
        expect(policy.scan(one(1, true), 5000), UsbAutoConnectPolicy.Kind.CONNECT, "retry IO failure");
    }

    private static void blocksDeniedPermissionUntilDetachOrExplicitRetry() {
        UsbAutoConnectPolicy policy = new UsbAutoConnectPolicy();
        expect(policy.scan(one(1, false), 0), UsbAutoConnectPolicy.Kind.REQUEST_PERMISSION, "initial permission");
        policy.onPermissionResult(1, false);
        expect(policy.scan(one(1, false), 60000), UsbAutoConnectPolicy.Kind.NONE, "do not nag after denial");
        policy.scan(Collections.emptyList(), 60001);
        expect(policy.scan(one(1, false), 60002), UsbAutoConnectPolicy.Kind.REQUEST_PERMISSION, "detach permits new dialog");
        policy.onPermissionResult(1, false);
        policy.resume();
        expect(policy.scan(one(1, false), 60003), UsbAutoConnectPolicy.Kind.REQUEST_PERMISSION, "explicit retry permits new dialog");
    }

    private static void stopsUntilExplicitResume() {
        UsbAutoConnectPolicy policy = new UsbAutoConnectPolicy();
        expect(policy.scan(one(1, true), 0), UsbAutoConnectPolicy.Kind.CONNECT, "initial connect before stop");
        policy.onConnected();
        policy.stop();
        expect(policy.scan(one(1, true), 100000), UsbAutoConnectPolicy.Kind.NONE, "manual stop must persist");
        policy.scan(Collections.emptyList(), 100001);
        expect(policy.scan(one(2, true), 100002), UsbAutoConnectPolicy.Kind.NONE, "reattach must not undo manual stop");
        policy.resume();
        expect(policy.scan(one(2, true), 100003), UsbAutoConnectPolicy.Kind.CONNECT, "explicit resume restarts scanning");
    }

    private static void forgetsDetachedConnectionsAndPermissionRequests() {
        UsbAutoConnectPolicy policy = new UsbAutoConnectPolicy();
        policy.scan(one(1, false), 0);
        policy.scan(Collections.emptyList(), 1);
        expect(policy.scan(one(2, false), 2), UsbAutoConnectPolicy.Kind.REQUEST_PERMISSION, "detached request does not block new device");
        policy.onPermissionResult(2, true);
        policy.scan(one(2, true), 3);
        policy.onConnected();
        policy.scan(Collections.emptyList(), 4);
        expect(policy.scan(one(3, true), 5), UsbAutoConnectPolicy.Kind.CONNECT, "detached connection does not block replacement");
    }

    private static void handlesDetachEventsBetweenScans() {
        UsbAutoConnectPolicy policy = new UsbAutoConnectPolicy();
        policy.scan(one(1, false), 0);
        policy.onPermissionResult(1, false);
        policy.onDetached(1);
        expect(policy.scan(one(1, false), 1), UsbAutoConnectPolicy.Kind.REQUEST_PERMISSION,
                "explicit detach clears denial even when the same ID reappears between scans");
        policy.onDetached(1);
        expect(policy.scan(one(1, false), 2), UsbAutoConnectPolicy.Kind.REQUEST_PERMISSION,
                "explicit detach clears pending permission request");
        policy.onPermissionResult(1, true);
        policy.scan(one(1, true), 3);
        policy.onConnected();
        policy.onDetached(1);
        expect(policy.scan(one(1, true), 4), UsbAutoConnectPolicy.Kind.CONNECT,
                "explicit detach clears established connection");
        policy.onConnectionFailed(5);
        policy.onDetached(1);
        expect(policy.scan(one(1, true), 2004), UsbAutoConnectPolicy.Kind.NONE,
                "detach preserves failure retry interval");
        expect(policy.scan(one(1, true), 2005), UsbAutoConnectPolicy.Kind.CONNECT,
                "retry remains available at interval boundary");
        policy.stop();
        policy.onDetached(1);
        expect(policy.scan(one(1, true), 5000), UsbAutoConnectPolicy.Kind.NONE,
                "detach does not undo manual stop");

        UsbAutoConnectPolicy manual = new UsbAutoConnectPolicy();
        manual.setPreferred(10, 20, 0);
        manual.setPreferredAttachedDevice(2);
        manual.onDetached(2);
        expect(manual.scan(Arrays.asList(candidate(1, 10, 20, 0, true),
                candidate(2, 10, 20, 0, true)), 0), UsbAutoConnectPolicy.Kind.NONE,
                "explicit detach clears transient physical device choice");
    }

    private static List<UsbAutoConnectPolicy.Candidate> one(int id, boolean permission) {
        return Collections.singletonList(candidate(id, 10, 20, 0, permission));
    }

    private static UsbAutoConnectPolicy.Candidate candidate(int id, int vendor, int product, int port, boolean permission) {
        return new UsbAutoConnectPolicy.Candidate(id, vendor, product, port, permission);
    }

    private static void expect(UsbAutoConnectPolicy.Action action, UsbAutoConnectPolicy.Kind kind, String message) {
        check(action.kind == kind, message + ": expected " + kind + ", got " + action.kind);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
