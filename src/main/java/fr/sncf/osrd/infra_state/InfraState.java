package fr.sncf.osrd.infra_state;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import fr.sncf.osrd.infra.Infra;
import fr.sncf.osrd.utils.DeepComparable;
import fr.sncf.osrd.utils.DeepEqualsUtils;

import java.util.Arrays;
import java.util.stream.Stream;

public final class InfraState implements DeepComparable<InfraState> {
    private final Infra infra;
    private final SignalState[] signalSignalStates;
    private final RouteState[] routeStates;
    private final SwitchState[] switchStates;
    private final TVDSectionState[] tvdSectionStates;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    private InfraState(
            Infra infra,
            SignalState[] signalSignalStates,
            RouteState[] routeStates,
            SwitchState[] switchStates,
            TVDSectionState[] tvdSectionStates
    ) {
        this.infra = infra;
        this.signalSignalStates = signalSignalStates;
        this.routeStates = routeStates;
        this.switchStates = switchStates;
        this.tvdSectionStates = tvdSectionStates;
    }

    public SignalState getSignalState(int signalIndex) {
        return signalSignalStates[signalIndex];
    }

    public RouteState getRouteState(int routeIndex) {
        return routeStates[routeIndex];
    }

    public Stream<RouteState> getRoutesStream() {
        return Arrays.stream(routeStates);
    }

    public SwitchState getSwitchState(int switchIndex) {
        return switchStates[switchIndex];
    }

    public TVDSectionState getTvdSectionState(int tvdSectionIndex) {
        return tvdSectionStates[tvdSectionIndex];
    }

    public Infra getInfra() {
        return infra;
    }

    @Override
    public boolean deepEquals(InfraState otherState) {
        if (!DeepEqualsUtils.deepEquals(signalSignalStates, otherState.signalSignalStates))
            return false;
        if (!DeepEqualsUtils.deepEquals(routeStates, otherState.routeStates))
            return false;
        if (!DeepEqualsUtils.deepEquals(switchStates, otherState.switchStates))
            return false;
        return DeepEqualsUtils.deepEquals(tvdSectionStates, otherState.tvdSectionStates);
    }

    /** Initializes a state for the infrastructure */
    @SuppressFBWarnings({"BC_UNCONFIRMED_CAST_OF_RETURN_VALUE"})
    public static InfraState from(Infra infra) {
        var signalCount = infra.signals.size();
        var signalStates = new SignalState[signalCount];
        for (int i = 0; i < signalCount; i++)
            signalStates[i] = SignalState.from(infra.signals.get(i));

        var routeCount = infra.routeGraph.getEdgeCount();
        var routeStates = new RouteState[routeCount];
        for (int i = 0; i < routeCount; i++)
            routeStates[i] = new RouteState(infra.routeGraph.getEdge(i));

        var switchCount = infra.switches.size();
        var switchStates = new SwitchState[switchCount];
        for (var infraSwitch : infra.switches)
            switchStates[infraSwitch.switchIndex] = new SwitchState(infraSwitch);

        var tvdSectionCount = infra.tvdSections.size();
        var tvdSectionStates = new TVDSectionState[tvdSectionCount];
        for (var tvdSection : infra.tvdSections.values())
            tvdSectionStates[tvdSection.index] = new TVDSectionState(tvdSection);

        return new InfraState(infra, signalStates, routeStates, switchStates, tvdSectionStates);
    }
}
