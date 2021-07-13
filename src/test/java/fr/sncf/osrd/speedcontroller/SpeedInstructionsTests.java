package fr.sncf.osrd.speedcontroller;

import static fr.sncf.osrd.Helpers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import fr.sncf.osrd.infra.*;
import fr.sncf.osrd.infra.trackgraph.SwitchPosition;
import fr.sncf.osrd.railjson.parser.RailJSONParser;
import fr.sncf.osrd.simulation.Simulation;
import fr.sncf.osrd.simulation.SimulationError;
import fr.sncf.osrd.railjson.schema.schedule.RJSAllowance;
import fr.sncf.osrd.railjson.schema.schedule.RJSAllowance.LinearAllowance.MarginType;
import fr.sncf.osrd.speedcontroller.generators.LinearAllowanceGenerator;
import fr.sncf.osrd.train.TrainSchedule;
import fr.sncf.osrd.infra.InvalidInfraException;
import fr.sncf.osrd.infra.trackgraph.SwitchPosition;
import fr.sncf.osrd.railjson.parser.RailJSONParser;
import fr.sncf.osrd.railjson.schema.schedule.RJSAllowance.LinearAllowance.MarginType;
import fr.sncf.osrd.simulation.Simulation;
import fr.sncf.osrd.simulation.SimulationError;
import fr.sncf.osrd.speedcontroller.generators.SpeedControllerGenerator;
import fr.sncf.osrd.train.Train;
import fr.sncf.osrd.train.events.TrainMoveEvent;
import fr.sncf.osrd.train.events.TrainReachesActionPoint;
import fr.sncf.osrd.train.phases.SignalNavigatePhase;
import org.jaxen.util.SingletonList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class SpeedInstructionsTests {

    public static class StaticSpeedController extends SpeedController {

        public final double speed;

        public StaticSpeedController(double speed) {
            super(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
            this.speed = speed;
        }

        @Override
        public SpeedDirective getDirective(double pathPosition) {
            return new SpeedDirective(speed);
        }

        @Override
        public SpeedController scaled(double scalingFactor) {
            return new StaticSpeedController(speed * scalingFactor);
        }

        @Override
        public boolean deepEquals(SpeedController other) {
            if (other instanceof StaticSpeedController)
                return Math.abs(((StaticSpeedController) other).speed - speed) < 1e-5;
            return false;
        }
    }

    /** Get a speed generator indicating the given speed at every point */
    public static SpeedControllerGenerator getStaticGenerator(double maxSpeed) {
        return new SpeedControllerGenerator(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY) {
            @Override
            public Set<SpeedController> generate(Simulation sim, TrainSchedule schedule, Set<SpeedController>
                    controllers) {
                return new HashSet<>(Collections.singletonList(new StaticSpeedController(maxSpeed)));
            }
        };
    }

    @Test
    public void testFollowTargetSpeed() throws InvalidInfraException {
        final var infra = getBaseInfra();

        SpeedControllerGenerator generator = getStaticGenerator(5);
        final var config = getConfigWithSpeedInstructions(SpeedInstructions.fromController(generator));

        var sim = Simulation.createFromInfra(RailJSONParser.parse(infra), 0, null);

        for (int i = 10; i < 150; i++)
            makeAssertEvent(sim, i, () -> getLastTrainSpeed(sim) < 5.5);
        run(sim, config);
    }

    @Test
    public void testCatchup() throws InvalidInfraException, SimulationError {
        final var infra = getBaseInfra();

        SpeedControllerGenerator generator = getStaticGenerator(10);
        final var config = getConfigWithSpeedInstructions(SpeedInstructions.fromController(generator));

        infra.switches.iterator().next().positionChangeDelay = 42;
        var sim = Simulation.createFromInfra(RailJSONParser.parse(infra), 0, null);
        sim.infraState.getSwitchState(0).setPosition(sim, SwitchPosition.RIGHT);

        makeAssertEvent(sim, 43, () -> isLate(sim));
        makeAssertEvent(sim, 150, () -> !isLate(sim));
        run(sim, config);
    }

    @Test
    public void testIsLate() throws InvalidInfraException, SimulationError {
        final var infra = getBaseInfra();
        final var config = getBaseConfigNoAllowance();

        infra.switches.iterator().next().positionChangeDelay = 20;
        var sim = Simulation.createFromInfra(RailJSONParser.parse(infra), 0, null);
        sim.infraState.getSwitchState(0).setPosition(sim, SwitchPosition.RIGHT);

        makeAssertEvent(sim, 30, () -> isLate(sim));
        run(sim, config);
    }

    @Test
    public void testIsNotLate() throws InvalidInfraException, SimulationError {
        final var infra = getBaseInfra();
        final var config = getBaseConfig();

        var sim = Simulation.createFromInfra(RailJSONParser.parse(infra), 0, null);

        for (int i = 1; i < 150; i++)
            makeAssertEvent(sim, i, () -> !isLate(sim));
        run(sim, config);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, 50, 200})
    public void testLinearAllowance(double value) throws InvalidInfraException {
        final var infra = getBaseInfra();
        assert infra != null;
        var params = new LinearAllowanceGenerator(0, Double.POSITIVE_INFINITY, value, MarginType.TIME);

        // base run, no margin
        final var config = getBaseConfigNoAllowance();
        var sim = Simulation.createFromInfra(RailJSONParser.parse(infra), 0, null);
        run(sim, config);
        var baseSimTime = sim.getTime();

        // Run with 50% margins
        final var configMargins = getConfigWithSpeedInstructions(SpeedInstructions.fromController(params));
        var sim2 = Simulation.createFromInfra(RailJSONParser.parse(infra), 0, null);
        run(sim2, configMargins);
        var marginsSimTime = sim2.getTime();
        var expected = baseSimTime * (1 + value / 100);
        assertEquals(expected, marginsSimTime, expected * 0.01);
    }

    /** Helper function: returns true if the train is late at the time it is called */
    public static boolean isLate(Simulation sim) {
        var event = getLastTrainEvent(sim);
        var trainState = sim.trains.get("Test.").getLastState();
        var secondsLate = trainState.trainSchedule.speedInstructions.secondsLate(
                event.pathPosition, event.time);
        return secondsLate > 1;
    }

    /** Helper function: returns the last speed update of the train */
    public static Train.TrainStateChange.SpeedUpdate getLastTrainEvent(Simulation sim) {
        var train = sim.trains.get("Test.");
        var lastEvent = train.lastScheduledEvent;
        Train.TrainStateChange.SpeedUpdates updates;
        if (lastEvent instanceof TrainReachesActionPoint) {
            var trainReachesActionPoint = (TrainReachesActionPoint) lastEvent;
            updates = trainReachesActionPoint.trainStateChange.positionUpdates;
        } else {
            var trainReachesActionPoint = (TrainMoveEvent) lastEvent;
            updates = trainReachesActionPoint.trainStateChange.positionUpdates;
        }
        for (int i = updates.size() - 1; i >= 0; i--)
            if (updates.get(i).time <= sim.getTime())
                return updates.get(i);
        return updates.get(0);
    }

    /** Get the last speed of the train */
    public static double getLastTrainSpeed(Simulation sim) {
        return getLastTrainEvent(sim).speed;
    }
}
