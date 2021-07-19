package fr.sncf.osrd;

import static org.junit.jupiter.api.Assertions.*;

import com.squareup.moshi.JsonReader;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import fr.sncf.osrd.config.Config;
import fr.sncf.osrd.config.JsonConfig;
import fr.sncf.osrd.infra.Infra;
import fr.sncf.osrd.infra.SuccessionTable;
import fr.sncf.osrd.railjson.parser.RJSSimulationParser;
import fr.sncf.osrd.railjson.schema.RJSSimulation;
import fr.sncf.osrd.railjson.schema.schedule.RJSTrainPhase;
import fr.sncf.osrd.infra.InvalidInfraException;
import fr.sncf.osrd.railjson.parser.exceptions.InvalidRollingStock;
import fr.sncf.osrd.railjson.parser.exceptions.InvalidSchedule;
import fr.sncf.osrd.railjson.schema.common.ID;
import fr.sncf.osrd.railjson.schema.common.RJSTrackLocation;
import fr.sncf.osrd.railjson.parser.exceptions.InvalidSuccession;
import fr.sncf.osrd.railjson.schema.infra.RJSInfra;
import fr.sncf.osrd.simulation.Simulation;
import fr.sncf.osrd.simulation.SimulationError;
import fr.sncf.osrd.simulation.TimelineEvent;
import fr.sncf.osrd.simulation.TimelineEventId;
import fr.sncf.osrd.speedcontroller.SpeedInstructions;
import fr.sncf.osrd.train.events.TrainCreatedEvent;
import fr.sncf.osrd.train.events.TrainMoveEvent;
import fr.sncf.osrd.train.events.TrainReachesActionPoint;
import fr.sncf.osrd.utils.PathUtils;
import fr.sncf.osrd.utils.SortedDoubleMap;
import fr.sncf.osrd.utils.moshi.MoshiUtils;
import okio.Okio;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

public class Helpers {

    private static final boolean saveCSVFiles = false;

    public static final class TestEvent extends TimelineEvent {
        public final String data;
        private final transient BiConsumer<Simulation, TestEvent> onOccurrenceCallback;
        private final transient BiConsumer<Simulation, TestEvent> onCancellationCallback;

        private TestEvent(
                TimelineEventId eventId,
                String data,
                BiConsumer<Simulation, TestEvent> onOccurrenceCallback,
                BiConsumer<Simulation, TestEvent> onCancellationCallback
        ) {
            super(eventId);
            this.data = data;
            this.onOccurrenceCallback = onOccurrenceCallback;
            this.onCancellationCallback = onCancellationCallback;
        }

        @Override
        protected void onOccurrence(Simulation sim) {
            if (onOccurrenceCallback != null)
                onOccurrenceCallback.accept(sim, this);
        }

        @Override
        protected void onCancellation(Simulation sim) {
            if (onCancellationCallback != null)
                onCancellationCallback.accept(sim, this);
        }

        @Override
        public String toString() {
            return data;
        }

        @Override
        @SuppressFBWarnings("BC_UNCONFIRMED_CAST")
        public boolean deepEquals(TimelineEvent other) {
            if (other.getClass() != TestEvent.class)
                return false;
            var o = (TestEvent) other;
            return o.data.equals(data)
                    && o.eventId.equals(eventId)
                    && o.onOccurrenceCallback == onOccurrenceCallback
                    && o.onCancellationCallback == onCancellationCallback;
        }

        /**
         * Plan a test event at a given time with no callbacks
         */
        public static TestEvent plan(
                Simulation sim,
                double eventTime,
                String data
        ) {
            return plan(sim, eventTime, data, null);
        }

        /**
         * Plan a test event at a given time with the specified callbacks on occurence
         */
        public static TestEvent plan(
                Simulation sim,
                double eventTime,
                String data,
                BiConsumer<Simulation, TestEvent> onOccurrenceCallback
        ) {
            return plan(sim, eventTime, data, onOccurrenceCallback, null);
        }

        /**
         * Plan a test event at a given time with the specified callbacks
         */
        public static TestEvent plan(
                Simulation sim,
                double eventTime,
                String data,
                BiConsumer<Simulation, TestEvent> onOccurrenceCallback,
                BiConsumer<Simulation, TestEvent> onCancellationCallback
        ) {
            var change = new TestEventPlanned(sim, eventTime, data, onOccurrenceCallback, onCancellationCallback);
            var event = change.apply(sim);
            sim.publishChange(change);
            return event;
        }

        public static class TestEventPlanned extends Simulation.TimelineEventCreated {
            public final String data;
            private final transient BiConsumer<Simulation, TestEvent> onOccurrenceCallback;
            private final transient BiConsumer<Simulation, TestEvent> onCancellationCallback;

            private TestEventPlanned(
                    Simulation sim,
                    double eventTime,
                    String data,
                    BiConsumer<Simulation, TestEvent> onOccurrenceCallback,
                    BiConsumer<Simulation, TestEvent> onCancellationCallback
            ) {
                super(sim, eventTime);
                this.data = data;
                this.onOccurrenceCallback = onOccurrenceCallback;
                this.onCancellationCallback = onCancellationCallback;
            }

            private TestEvent apply(Simulation sim) {
                var event = new TestEvent(eventId, data, onOccurrenceCallback, onCancellationCallback);
                super.scheduleEvent(sim, event);
                return event;
            }

            @Override
            public void replay(Simulation sim) {
                apply(sim);
            }

            @Override
            public String toString() {
                return String.format("TestEventPlanned { eventId=%s, data=%s }", eventId, data);
            }
        }
    }

    /**
     * Generates the defaults infra from tiny_infra/infra.json, to be edited for each test
     */
    public static RJSInfra getBaseInfra() {
        return getBaseInfra("tiny_infra/infra.json");
    }

    /**
     * Generates the defaults infra from the specified path
     */
    public static RJSInfra getBaseInfra(String path) {
        try {
            var fileSource = Okio.source(getResourcePath(path));
            var bufferedSource = Okio.buffer(fileSource);
            var jsonReader = JsonReader.of(bufferedSource);
            return RJSInfra.adapter.fromJson(jsonReader);
        } catch (IOException e) {
            fail(e);
            throw new RuntimeException();
        }
    }

    /**
     * Generates the defaults config from tiny_infra/config_railjson.json
     */
    public static Config getBaseConfig(String path) {
        try {
            return Config.readFromFile(getResourcePath(path));
        } catch (IOException | InvalidInfraException | InvalidRollingStock | InvalidSchedule | InvalidSuccession e) {
            fail(e);
            throw new RuntimeException();
        }
    }

    /**
     * Generates the defaults config from tiny_infra/config_railjson.json
     */
    public static Config getBaseConfig() {
        return getBaseConfig("tiny_infra/config_railjson.json");
    }

    /**
     * Generates the defaults config from tiny_infra/config_railjson.json without allowances
     */
    public static Config getBaseConfigNoAllowance() {
        return getConfigWithSpeedInstructions(new SpeedInstructions(null));
    }

    /** Gets the default config but with the given SpeedInstruction in train schedules */
    public static Config getConfigWithSpeedInstructions(SpeedInstructions instructions) {
        var config = getBaseConfig("tiny_infra/config_railjson.json");
        config.trainSchedules.forEach(schedule -> schedule.speedInstructions = instructions);
        return config;
    }

    /** Loads the phases in the given simulation file
     * The purpose of this function is to edit the phases and call makeCOnfigWithGivenPhases afterwards */
    public static RJSTrainPhase[] loadRJSPhases(String simulationPath) {
        try {
            var path = getResourcePath(simulationPath);
            var schedule = MoshiUtils.deserialize(RJSSimulation.adapter, path);
            return schedule.trainSchedules.stream().findAny().orElseThrow().phases;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Loads the given config, but replaces the given phases in the schedule */
    public static Config makeConfigWithGivenPhases(RJSTrainPhase[] phases, String baseConfigPath) {
        try {
            var path = getResourcePath(baseConfigPath);
            var baseDirPath = path.getParent();
            var jsonConfig = MoshiUtils.deserialize(JsonConfig.adapter, path);
            final var infraPath = PathUtils.relativeTo(baseDirPath, jsonConfig.infraPath);
            final var infra = Infra.parseFromFile(jsonConfig.infraType, infraPath.toString());
            var schedulePath = PathUtils.relativeTo(baseDirPath, jsonConfig.simulationPath);
            var schedule = MoshiUtils.deserialize(RJSSimulation.adapter, schedulePath);
            for (var trainSchedule : schedule.trainSchedules) {
                trainSchedule.phases = phases;
            }
            var trainSchedules = RJSSimulationParser.parse(infra, schedule);
            var successionTables = new ArrayList<SuccessionTable>();
            for (var s : infra.switches) {
                successionTables.add(new SuccessionTable(s.id, new ArrayList<String>()));
            }
            return new Config(
                    jsonConfig.simulationTimeStep,
                    infra,
                    trainSchedules,
                    successionTables,
                    jsonConfig.simulationStepPause,
                    jsonConfig.showViewer,
                    jsonConfig.realTimeViewer,
                    jsonConfig.changeReplayCheck
            );
        } catch (IOException | InvalidInfraException | InvalidRollingStock | InvalidSchedule e) {
            fail(e);
            throw new RuntimeException();
        }
    }

    /** Go through all the events in the simulation, fails if an exception is thrown */
    public static ArrayList<TimelineEvent> run(Simulation sim) {
        final var config = getBaseConfig();
        return run(sim, config);
    }

    /** Go through all the events in the simulation with a specified config, fails if an exception is thrown */
    public static ArrayList<TimelineEvent> run(Simulation sim, Config config) {
        try {
            return runWithExceptions(sim, config);
        } catch (SimulationError e) {
            fail(e);
            throw new RuntimeException();
        }
    }

    /** Given a resource path find the full path (works cross platform) */
    public static Path getResourcePath(String resourcePath) {
        ClassLoader classLoader = Helpers.class.getClassLoader();
        var url = classLoader.getResource(resourcePath);
        assert url != null;
        try {
            return new File(url.toURI()).toPath();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    /** Go through all the events in the simulation, exceptions pass through */
    public static ArrayList<TimelineEvent> runWithExceptions(Simulation sim) throws SimulationError {
        final var config = getBaseConfig();
        return runWithExceptions(sim, config);
    }

    /** Go through all the events in the simulation with a specified config, exceptions pass through */
    public static ArrayList<TimelineEvent> runWithExceptions(Simulation sim, Config config) throws SimulationError {
        var events = new ArrayList<TimelineEvent>();
        for (var trainSchedule : config.trainSchedules)
            TrainCreatedEvent.plan(sim, trainSchedule);
        while (!sim.isSimulationOver())
            events.add(sim.step());
        return events;
    }

    /** Generates an event that runs an assertion at a certain point in the simulation */
    public static void makeAssertEvent(Simulation sim, double time, Supplier<Boolean> predicate) {
        Procedure func = () -> assertTrue(predicate.get());
        makeFunctionEvent(sim, time, func);
    }

    /** Generates an event that runs a function at a certain point in the simulation */
    public static void makeFunctionEvent(Simulation sim, double time, Procedure func) {
        BiConsumer<Simulation, TestEvent> consumer = (s, test) -> {
            try {
                func.run();
            } catch (Exception e) {
                fail(e);
            }
        };
        TestEvent.plan(sim, time, null, consumer);
    }

    /** Simple class similar to java Runnable, but with exceptions */
    public interface Procedure {
        void run() throws Exception;
    }

    /** Get a map of the time at which each position is reached */
    public static SortedDoubleMap getTimePerPosition(Iterable<TimelineEvent> events) {
        var res = new SortedDoubleMap();
        for (var event : events) {
            if (event instanceof TrainReachesActionPoint) {
                var trainReachesActionPoint = (TrainReachesActionPoint) event;
                for (var update : trainReachesActionPoint.trainStateChange.positionUpdates)
                    res.put(update.pathPosition, update.time);
            }
        }
        return res;
    }

    /** Throws an error if the (interpolated) time per position differ too much */
    public static void assertSameSpeedPerPosition(Iterable<TimelineEvent> eventsExpected,
                                                  Iterable<TimelineEvent> events) {
        assertSameSpeedPerPositionBetween(eventsExpected, events, 0, Double.POSITIVE_INFINITY, 1);
    }

    /** Throws an error if the (interpolated) time per position differ too much between begin and end
     * The expected speeds are scaled by expectedScale */
    public static void assertSameSpeedPerPositionBetween(Iterable<TimelineEvent> eventsExpected,
                                                         Iterable<TimelineEvent> events,
                                                         double begin,
                                                         double end,
                                                         double expectedScale) {
        var expectedSpeedPerPosition = getSpeedPerPosition(eventsExpected);
        var speedPerPosition = getSpeedPerPosition(events);
        end = Double.min(end, expectedSpeedPerPosition.lastKey());
        begin = Double.max(expectedSpeedPerPosition.firstKey(), begin);
        for (double t = begin; t < end; t += 1) {
            var expected = expectedSpeedPerPosition.interpolate(t) * expectedScale;
            var result = speedPerPosition.interpolate(t);
            assertEquals(expected, result, expected * 0.01);
        }
    }

    /** Get a map of the speed at each position */
    public static SortedDoubleMap getSpeedPerPosition(Iterable<TimelineEvent> events) {
        var res = new SortedDoubleMap();
        for (var event : events) {
            if (event instanceof TrainReachesActionPoint) {
                var trainReachesActionPoint = (TrainReachesActionPoint) event;
                for (var update : trainReachesActionPoint.trainStateChange.positionUpdates)
                    res.put(update.pathPosition, update.speed);
            }
        }
        return res;
    }

    /** Get a list of 2 phases on the base infra, with the transition happening roughly half-way */
    public static RJSTrainPhase[] loadPhasesLongerFirstPhase() {
        var phases = loadRJSPhases("tiny_infra/simulation_several_phases.json");
        phases[0].endLocation = new RJSTrackLocation(new ID<>("ne.micro.foo_to_bar"), 4000);
        return phases;
    }

    /** Saves a csv files with the time, speed and positions. For debugging purpose. */
    public static void saveGraph(ArrayList<TimelineEvent> events, String path) {
        if (!saveCSVFiles)
            return;
        if (events == null)
            throw new RuntimeException();
        try {
            PrintWriter writer = new PrintWriter(path, "UTF-8");
            writer.println("position,time,speed");
            for (var event : events) {
                if (event instanceof TrainReachesActionPoint) {
                    var updates = ((TrainReachesActionPoint) event).trainStateChange.positionUpdates;
                    for (var update : updates) {
                        writer.println(String.format("%f,%f,%f", update.pathPosition, update.time, update.speed));
                    }
                } else if (event instanceof TrainMoveEvent) {
                    var updates = ((TrainMoveEvent) event).trainStateChange.positionUpdates;
                    for (var update : updates) {
                        writer.println(String.format("%f,%f,%f", update.pathPosition, update.time, update.speed));
                    }
                }
            }
            writer.close();
        } catch (FileNotFoundException | UnsupportedEncodingException e) {
            e.printStackTrace();
        }
    }
}
