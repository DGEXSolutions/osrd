package fr.sncf.osrd.train;

import static fr.sncf.osrd.Helpers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import fr.sncf.osrd.infra.InvalidInfraException;
import fr.sncf.osrd.infra.trackgraph.SwitchPosition;
import fr.sncf.osrd.infra_state.RouteState;
import fr.sncf.osrd.infra_state.RouteStatus;
import fr.sncf.osrd.infra_state.SwitchState;
import fr.sncf.osrd.railjson.parser.RailJSONParser;
import fr.sncf.osrd.simulation.Simulation;
import fr.sncf.osrd.simulation.SimulationError;
import org.junit.jupiter.api.Test;

public class PhasesTest {

    @Test
    public void testSameSimulationEndTime() throws InvalidInfraException {
        final var infra = getBaseInfra();

        final var config = getBaseConfig("tiny_infra/config_railjson_several_phases.json");
        var sim = Simulation.createFromInfraAndEmptySuccessions(RailJSONParser.parse(infra), 0, null);
        run(sim, config);
        var actualEndTime = sim.getTime();

        final var configBase = getBaseConfig();
        var simBase = Simulation.createFromInfraAndEmptySuccessions(RailJSONParser.parse(infra), 0, null);
        run(simBase, configBase);
        var baseEndTime = simBase.getTime();

        assertEquals(baseEndTime, actualEndTime, baseEndTime * 0.1);
    }

    @Test
    public void testSameEventTimes() throws InvalidInfraException {
        final var infra = getBaseInfra();

        final var config = getBaseConfig("tiny_infra/config_railjson_several_phases.json");
        var sim = Simulation.createFromInfraAndEmptySuccessions(RailJSONParser.parse(infra), 0, null);
        var events = run(sim, config);

        final var configBase = getBaseConfigNoAllowance();
        var simBase = Simulation.createFromInfraAndEmptySuccessions(RailJSONParser.parse(infra), 0, null);
        var eventsRef = run(simBase, configBase);

        assertEquals(eventsRef.size() + 1, events.size());

        var resultTimePerPosition = getTimePerPosition(events);
        var expectedTimePerPosition = getTimePerPosition(eventsRef);

        for (double t = expectedTimePerPosition.firstKey(); t < expectedTimePerPosition.lastKey(); t += 1) {
            var expected = expectedTimePerPosition.interpolate(t);
            var result = resultTimePerPosition.interpolate(t);
            assertEquals(expected, result, expected * 0.01);
        }
    }

    @Test
    public void testReactToSignals() throws InvalidInfraException, SimulationError {
        final var infra = getBaseInfra();
        final var config = getBaseConfig("tiny_infra/config_railjson_several_phases.json");

        infra.switches.iterator().next().positionChangeDelay = 500;

        var sim = Simulation.createFromInfraAndEmptySuccessions(RailJSONParser.parse(infra), 0, null);
        sim.infraState.getSwitchState(0).setPosition(sim, SwitchPosition.RIGHT);

        // If the train ignores the signals, an exception will be thrown when it runs over the moving switch
        run(sim, config);
    }

    @Test
    public void testTriggerSwitchChangeAtRightTime() throws InvalidInfraException, SimulationError {
        final var infra = getBaseInfra();
        final var config = getBaseConfig("tiny_infra/config_railjson_several_phases.json");

        infra.switches.iterator().next().positionChangeDelay = 42;

        var sim = Simulation.createFromInfraAndEmptySuccessions(RailJSONParser.parse(infra), 0, null);
        sim.infraState.getSwitchState(0).setPosition(sim, SwitchPosition.RIGHT);

        SwitchState switchState = sim.infraState.getSwitchState(0);
        RouteState routeState = sim.infraState.getRouteState(3);
        makeAssertEvent(sim, 0, () -> switchState.getPosition() == SwitchPosition.RIGHT);
        makeAssertEvent(sim, 41, () -> switchState.getPosition() == SwitchPosition.MOVING);
        makeAssertEvent(sim, 41, () -> routeState.status == RouteStatus.REQUESTED);
        makeAssertEvent(sim, 43, () -> switchState.getPosition() == SwitchPosition.LEFT);
        makeAssertEvent(sim, 43, () -> routeState.status == RouteStatus.RESERVED);

        run(sim, config);
    }
}
