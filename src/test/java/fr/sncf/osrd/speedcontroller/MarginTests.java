package fr.sncf.osrd.speedcontroller;

import static fr.sncf.osrd.Helpers.*;
import static fr.sncf.osrd.railjson.schema.schedule.RJSAllowance.LinearAllowance.MarginType.TIME;
import static java.lang.Double.POSITIVE_INFINITY;
import static org.junit.jupiter.api.Assertions.assertEquals;

import fr.sncf.osrd.speedcontroller.generators.ConstructionAllowanceGenerator;
import fr.sncf.osrd.speedcontroller.generators.LinearAllowanceGenerator;
import fr.sncf.osrd.speedcontroller.generators.MarecoAllowanceGenerator;
import fr.sncf.osrd.speedcontroller.generators.SpeedControllerGenerator;
import fr.sncf.osrd.train.TrainSchedule;
import fr.sncf.osrd.infra.InvalidInfraException;
import fr.sncf.osrd.railjson.parser.RailJSONParser;
import fr.sncf.osrd.railjson.schema.schedule.RJSAllowance;
import fr.sncf.osrd.simulation.Simulation;
import fr.sncf.osrd.utils.TrackSectionLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import java.util.Arrays;
import java.util.HashSet;

public class MarginTests {

    @Test
    public void testConstructionMargins() throws InvalidInfraException {
        final var infra = getBaseInfra();
        assert infra != null;
        var params = new ConstructionAllowanceGenerator(0, POSITIVE_INFINITY, 30);

        // base run, no margin
        final var config = getBaseConfigNoAllowance();
        var sim = Simulation.createFromInfra(RailJSONParser.parse(infra), 0, null);
        var eventsBase = run(sim, config);
        var baseSimTime = sim.getTime();

        // Run with construction margin
        final var configMargins = getConfigWithSpeedInstructions(SpeedInstructions.fromController(params));
        var sim2 = Simulation.createFromInfra(RailJSONParser.parse(infra), 0, null);
        var events = run(sim2, configMargins);
        var marginsSimTime = sim2.getTime();

        var expected = baseSimTime + params.value;

        assertEquals(expected, marginsSimTime, expected * 0.01);
        saveGraph(eventsBase, "construction-base.csv");
        saveGraph(events, "construction-out.csv");
    }

    @Test
    public void testConstructionOnLinearMargin() throws InvalidInfraException {
        final var infra = getBaseInfra();
        assert infra != null;
        var params1 = new LinearAllowanceGenerator(0, POSITIVE_INFINITY,
                10, TIME);
        var params2 = new ConstructionAllowanceGenerator(0, POSITIVE_INFINITY, 15);

        var params = new ArrayList<SpeedControllerGenerator>();
        params.add(params1);
        params.add(params2);

        // Run with construction margin
        final var configMargins = getConfigWithSpeedInstructions(SpeedInstructions.fromList(params));
        var sim2 = Simulation.createFromInfra(RailJSONParser.parse(infra), 0, null);
        var events = run(sim2, configMargins);
        var marginsSimTime = sim2.getTime();

        // base run, no margin
        final var config = getBaseConfigNoAllowance();
        var sim = Simulation.createFromInfra(RailJSONParser.parse(infra), 0, null);
        var eventsBase = run(sim, config);
        var baseSimTime = sim.getTime();

        var expected = baseSimTime * 1.1 + 15;

        assertEquals(expected, marginsSimTime, expected * 0.01);

        saveGraph(eventsBase, "linear-time-on-construction-base.csv");
        saveGraph(events, "linear-time-on-construction-out.csv");
    }

    @Test
    public void testLargerEcoMargin() throws InvalidInfraException {
        final var infra = getBaseInfra();
        var params = new MarecoAllowanceGenerator(0, POSITIVE_INFINITY,
                200, RJSAllowance.MarecoAllowance.MarginType.TIME);

        // Run with construction margin
        final var configMargins = getConfigWithSpeedInstructions(SpeedInstructions.fromController(params));
        var sim2 = Simulation.createFromInfra(RailJSONParser.parse(infra), 0, null);
        var events = run(sim2, configMargins);
        var marginsSimTime = sim2.getTime();

        // base run, no margin
        final var config = getBaseConfigNoAllowance();
        var sim = Simulation.createFromInfra(RailJSONParser.parse(infra), 0, null);
        var eventsBase = run(sim, config);
        var baseSimTime = sim.getTime();

        var expected = baseSimTime * (1 + params.value / 100);
        assertEquals(expected, marginsSimTime, expected * 0.1);

        saveGraph(eventsBase, "eco-base.csv");
        saveGraph(events, "eco-out.csv");
    }

    @Test
    public void testEcoMargin() throws InvalidInfraException {
        final var infra = getBaseInfra();
        assert infra != null;
        var params = new MarecoAllowanceGenerator(0, POSITIVE_INFINITY,
                10, RJSAllowance.MarecoAllowance.MarginType.TIME);

        // Run with construction margin
        final var configMargins = getConfigWithSpeedInstructions(SpeedInstructions.fromController(params));
        var sim2 = Simulation.createFromInfra(RailJSONParser.parse(infra), 0, null);
        var events = run(sim2, configMargins);
        var marginsSimTime = sim2.getTime();

        // base run, no margin
        final var config = getBaseConfigNoAllowance();
        var sim = Simulation.createFromInfra(RailJSONParser.parse(infra), 0, null);
        var eventsBase = run(sim, config);
        var baseSimTime = sim.getTime();

        var expected = baseSimTime * 1.1;
        assertEquals(expected, marginsSimTime, expected * 0.01);

        saveGraph(eventsBase, "eco-base.csv");
        saveGraph(events, "eco-out.csv");
    }

    @Test
    public void testConstructionMarginsNoAllowance() throws InvalidInfraException {
        final var infra = getBaseInfra();
        assert infra != null;
        var params = new ConstructionAllowanceGenerator(0, POSITIVE_INFINITY, 0);

        // base run, no margin
        final var config = getBaseConfigNoAllowance();
        var sim = Simulation.createFromInfra(RailJSONParser.parse(infra), 0, null);
        run(sim, config);
        var baseSimTime = sim.getTime();

        // Run with construction margin
        final var configMargins = getConfigWithSpeedInstructions(SpeedInstructions.fromController(params));
        var sim2 = Simulation.createFromInfra(RailJSONParser.parse(infra), 0, null);
        run(sim2, configMargins);
        var marginsSimTime = sim2.getTime();

        assertEquals(baseSimTime, marginsSimTime, 0.5);
    }

    @Test
    public void testTimeMargin() throws InvalidInfraException {
        final var infra = getBaseInfra();
        assert infra != null;
        var params = new LinearAllowanceGenerator(0, POSITIVE_INFINITY,
                20, TIME);

        // base run, no margin
        final var config = getBaseConfigNoAllowance();
        var sim = Simulation.createFromInfra(RailJSONParser.parse(infra), 0, null);
        var eventsBase = run(sim, config);
        var baseSimTime = sim.getTime();

        // Run with 20% margins
        final var configMargins = getConfigWithSpeedInstructions(SpeedInstructions.fromController(params));
        var sim2 = Simulation.createFromInfra(RailJSONParser.parse(infra), 0, null);
        var events = run(sim2, configMargins);
        var marginsSimTime = sim2.getTime();

        var expected = baseSimTime * 1.2;

        saveGraph(eventsBase, "linear-time-base.csv");
        saveGraph(events, "linear-time-out.csv");
        assertEquals(expected, marginsSimTime, expected * 0.01);
    }

    @Test
    public void testDistanceMargin() throws InvalidInfraException {
        final var infra = getBaseInfra();
        assert infra != null;
        var params = new LinearAllowanceGenerator(0, POSITIVE_INFINITY,
                270, RJSAllowance.LinearAllowance.MarginType.DISTANCE);

        // base run, no margin
        final var config = getBaseConfigNoAllowance();
        var sim = Simulation.createFromInfra(RailJSONParser.parse(infra), 0, null);
        var eventsBase = run(sim, config);
        var baseSimTime = sim.getTime();

        // Run with 50% margins
        final var configMargins = getConfigWithSpeedInstructions(SpeedInstructions.fromController(params));
        var sim2 = Simulation.createFromInfra(RailJSONParser.parse(infra), 0, null);
        var events = run(sim2, configMargins);
        var marginsSimTime = sim2.getTime();

        var schedule = configMargins.trainSchedules.get(0);
        var start = schedule.initialLocation;
        var end = schedule.phases.get(0).getEndLocation();
        var distance = convertTrackLocation(end, schedule) - convertTrackLocation(start, schedule);
        var expectedExtraTime = params.value * distance / 100000;
        var expected = baseSimTime + expectedExtraTime;

        assertEquals(expected, marginsSimTime, expected * 0.01);
        saveGraph(eventsBase, "linear-distance-base.csv");
        saveGraph(events, "linear-distance-out.csv");
    }

    @Test
    public void testSameSpeedLimits() throws InvalidInfraException {
        final var infra = getBaseInfra();

        double marginChangeLocation = 5000;

        var params1 = new LinearAllowanceGenerator(0, marginChangeLocation, 50, TIME);
        var params2 = new LinearAllowanceGenerator(marginChangeLocation, POSITIVE_INFINITY,
                50, TIME);

        var params = new HashSet<SpeedControllerGenerator>();
        params.add(params1);
        params.add(params2);

        // Run with margins
        final var configMargins = getConfigWithSpeedInstructions(SpeedInstructions.fromSet(params));
        var sim2 = Simulation.createFromInfra(RailJSONParser.parse(infra), 0, null);
        var events = run(sim2, configMargins);

        // base run, one global margin
        var globalParams = new LinearAllowanceGenerator(0, POSITIVE_INFINITY, 50, TIME);
        final var config = getConfigWithSpeedInstructions(SpeedInstructions.fromController(globalParams));
        var sim = Simulation.createFromInfra(RailJSONParser.parse(infra), 0, null);
        var eventsBase = run(sim, config);

        saveGraph(eventsBase, "same-margins-base.csv");
        saveGraph(events, "same-margins-out.csv");

        assertSameSpeedPerPosition(eventsBase, events);
    }


    @Test
    public void testDifferentSpeedLimits() throws InvalidInfraException {
        final var infra = getBaseInfra();

        double marginChangeLocation = 5000;

        // base run, no margin
        final var config = getBaseConfigNoAllowance();
        var sim = Simulation.createFromInfra(RailJSONParser.parse(infra), 0, null);
        var eventsBase = run(sim, config);
        var baseTimePerPosition = getTimePerPosition(eventsBase);
        final var marginChangeTime = baseTimePerPosition.interpolate(marginChangeLocation);
        final var totalTime = sim.getTime();

        var params1 = new LinearAllowanceGenerator(0, marginChangeLocation, 20, TIME);
        var params2 = new LinearAllowanceGenerator(marginChangeLocation, POSITIVE_INFINITY, 60, TIME);

        var params = new HashSet<SpeedControllerGenerator>();
        params.add(params1);
        params.add(params2);

        // Run with margins
        final var configMargins = getConfigWithSpeedInstructions(SpeedInstructions.fromSet(params));
        var sim2 = Simulation.createFromInfra(RailJSONParser.parse(infra), 0, null);
        var events = run(sim2, configMargins);
        var marginTime = sim2.getTime();

        saveGraph(eventsBase, "different-margins-base.csv");
        saveGraph(events, "different-margins-out.csv");

        var expected = marginChangeTime * (1 + params1.value / 100)
                + (totalTime - marginChangeTime) * (1 + params2.value / 100);
        assertEquals(expected, marginTime, expected * 0.01);
    }

    @Test
    public void testSeveralConstructionMargins() throws InvalidInfraException {
        final var infra = getBaseInfra();
        var param1 = new ConstructionAllowanceGenerator(0, 5000, 15);
        var param2 = new ConstructionAllowanceGenerator(5000, POSITIVE_INFINITY, 30);

        final var config = getConfigWithSpeedInstructions(
                SpeedInstructions.fromList(Arrays.asList(param1, param2)));
        var sim = Simulation.createFromInfra(RailJSONParser.parse(infra), 0, null);
        saveGraph(run(sim, config), "double-construction-out.csv");
        var actualEndTime = sim.getTime();

        final var configBase = getBaseConfigNoAllowance();
        var simBase = Simulation.createFromInfra(RailJSONParser.parse(infra), 0, null);
        saveGraph(run(simBase, configBase), "double-construction-base.csv");
        var baseEndTime = simBase.getTime();

        var expected = baseEndTime + param1.value + param2.value;

        assertEquals(expected, actualEndTime, expected * 0.01);
    }

    private double convertTrackLocation(TrackSectionLocation location, TrainSchedule schedule) {
        double sumPreviousSections = 0;
        for (var edge : schedule.plannedPath.trackSectionPath) {
            if (edge.containsLocation(location)) {
                return sumPreviousSections + location.offset;
            }
            sumPreviousSections += edge.getEndPosition() - edge.getBeginPosition();
        }
        throw new RuntimeException("Can't find location in path");
    }

    @Test
    public void testDifferentMargins() throws InvalidInfraException {
        final var infra = getBaseInfra();

        var paramsFirstPhase = new LinearAllowanceGenerator(0, 5000, 10, TIME);
        var paramsSecondPhase = new LinearAllowanceGenerator(5000, POSITIVE_INFINITY, 60, TIME);
        var params = new HashSet<SpeedControllerGenerator>();
        params.add(paramsFirstPhase);
        params.add(paramsSecondPhase);

        final var configMargins = getConfigWithSpeedInstructions(SpeedInstructions.fromSet(params));
        var simMargins = Simulation.createFromInfra(RailJSONParser.parse(infra), 0, null);
        final var eventsMargins = run(simMargins, configMargins);

        final var config = getBaseConfigNoAllowance();
        var sim = Simulation.createFromInfra(RailJSONParser.parse(infra), 0, null);
        var baseEvents = run(sim, config);

        saveGraph(eventsMargins, "two-margins-out.csv");
        saveGraph(baseEvents, "two-margins-base.csv");

        // We don't test the whole range as the speeds can be *slightly* different
        // during the transition or when close to 0 (see also issue with shifted speed limits)
        assertSameSpeedPerPositionBetween(baseEvents, eventsMargins, 10, 2000,
                1 / (1 + paramsFirstPhase.value / 100));
        assertSameSpeedPerPositionBetween(baseEvents, eventsMargins, 6000, 9000,
                1 / (1 + paramsSecondPhase.value / 100));
    }
}
