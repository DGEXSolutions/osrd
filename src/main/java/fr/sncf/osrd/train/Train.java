package fr.sncf.osrd.train;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import fr.sncf.osrd.infra.signaling.Signal;
import fr.sncf.osrd.infra.trackgraph.Detector;
import fr.sncf.osrd.infra_state.SignalState;
import fr.sncf.osrd.simulation.*;
import fr.sncf.osrd.speedcontroller.SpeedController;
import fr.sncf.osrd.speedcontroller.SpeedDirective;
import fr.sncf.osrd.utils.CryoList;
import fr.sncf.osrd.utils.DeepComparable;
import fr.sncf.osrd.utils.DeepEqualsUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Set;

public class Train {
    static final Logger logger = LoggerFactory.getLogger(Train.class);

    public String getName() {
        return schedule.trainID;
    }

    private TrainState lastState;
    public final TrainSchedule schedule;
    public TimelineEvent lastScheduledEvent = null;

    private Train(TrainSchedule schedule, TrainState initialState) {
        this.schedule = schedule;
        this.lastState = initialState;
    }

    /** Create a train */
    public static Train create(
            Simulation sim,
            TrainSchedule schedule
    ) throws SimulationError {
        schedule.speedInstructions.generate(sim, schedule);
        var phaseState = schedule.phases.get(0).getState(sim, schedule);
        var location = getInitialLocation(schedule, sim);
        var initialState = new TrainState(
                sim.getTime(),
                location,
                schedule.initialSpeed,
                TrainStatus.STARTING_UP,
                schedule,
                0,
                phaseState,
                new ArrayDeque<>(),
                new TrainPath(schedule.plannedPath),
                0
        );

        ActivateRoute.trainCreation(sim, initialState);

        var trainCreatedChange = new TrainCreatedChange(sim, schedule, initialState);
        var train = trainCreatedChange.apply(sim);
        sim.publishChange(trainCreatedChange);
        train.scheduleStateChange(sim);
        return train;
    }

    /** Generates the initial location object of a train given its schedule */
    public static TrainPositionTracker getInitialLocation(TrainSchedule schedule, Simulation sim) {
        // the train starts out as a point like object on the beginning of the route
        var initialPosition = new ArrayDeque<TrackSectionRange>();
        var initialLocation = schedule.initialLocation;
        initialPosition.addFirst(new TrackSectionRange(
                initialLocation.edge,
                schedule.initialDirection,
                initialLocation.offset,
                initialLocation.offset // This starts as a 0 length range, the train grow in size as it appears
        ));
        var trackSectionPath = schedule.plannedPath.trackSectionPath;
        return new TrainPositionTracker(sim.infra, sim.infraState, initialPosition, trackSectionPath);
    }

    /** Returns the last TrainState */
    public TrainState getLastState() {
        return lastState;
    }

    // region ENTITY_REACTOR

    /** Simulate the current state to schedule the next one */
    public void scheduleStateChange(Simulation sim) throws SimulationError {
        if (lastScheduledEvent != null && lastScheduledEvent.getState() == TimelineEvent.State.SCHEDULED)
            return;
        if (lastState.status == TrainStatus.REACHED_DESTINATION) {
            logger.info("train {} reached destination, aborting planning", getName());
            return;
        }

        logger.info("planning the next move for train {}", getName());
        var clonedState = lastState.clone();
        clonedState.time = sim.getTime();
        lastScheduledEvent = clonedState.simulatePhase(this, sim);
    }

    /** Reserve routes when the train is in navigate phase */
    public void onEventOccurred(Simulation sim) throws SimulationError {
        // TODO find a smarter way to do it and remove this method
        ActivateRoute.reserveRoutes(sim, lastState);
    }

    // endregion

    // region INTERACTIONS
    /** Make the train interact with a detector */
    public void interact(Simulation sim, Detector detector, InteractionType interactionType) throws SimulationError {
        lastState.updateTVDSections(sim, detector, interactionType);
    }

    /** Make the train interact with a signal */
    public void interact(Simulation sim, Signal signal, InteractionType interactionType) {
        var signalState = sim.infraState.getSignalState(signal.index);

        switch (interactionType) {
            case SEEN:
                signalState.subscribeTrain(this);
                lastState.setAspectConstraints(signalState);
                break;
            case HEAD:
                signalState.unsubscribeTrain();
                break;
            default:
                throw new RuntimeException("Unexpected signal interaction type");
        }
    }

    /** Notify the train that a signal has change aspects and it have to re-evaluate its planned state */
    public void reactNewAspects(Simulation sim, SignalState signalState) throws SimulationError {
        if (lastScheduledEvent != null && lastScheduledEvent.getState() == TimelineEvent.State.SCHEDULED) {
            // Cancel last scheduled event
            sim.cancel(lastScheduledEvent);
            // Recompute the state until current simulation time
            var newState = lastState.clone();
            var stateChange = newState.evolveStateUntilNow(sim);
            stateChange.apply(sim, this);
            sim.publishChange(stateChange);
        }
        // Change/Add aspect constraints
        lastState.setAspectConstraints(signalState);
        // Schedule the next train state
        scheduleStateChange(sim);
    }
    // endregion

    // region CHANGES

    public static final class TrainCreatedChange extends SimChange<Train> {
        public final TrainSchedule schedule;
        public final TrainState initialState;

        /** A change corresponding to a train's creation. */
        public TrainCreatedChange(
                Simulation sim,
                TrainSchedule schedule,
                TrainState initialState
        ) {
            super(sim);
            this.schedule = schedule;
            this.initialState = initialState;
        }

        @Override
        public Train apply(Simulation sim) {
            var train = new Train(schedule, initialState.clone());
            sim.trains.put(train.getName(), train);
            return train;
        }

        @Override
        public String toString() {
            return String.format("TrainCreatedChange { name=%s }", schedule.trainID);
        }
    }

    public static class TrainStateChange extends EntityChange<Train, Void> implements DeepComparable<TrainStateChange> {
        public final String trainID;
        public final TrainState newState;
        public final SpeedUpdates positionUpdates = new SpeedUpdates();
        public final PathUpdates<Set<SpeedController>> speedControllersUpdates = new PathUpdates<>();
        public final PathUpdates<SpeedDirective> speedDirectivesUpdates = new PathUpdates<>();

        /** Creates a change corresponding to the movement of a train */
        public TrainStateChange(Simulation sim, String trainID, TrainState newState) {
            super(sim);
            this.trainID = trainID;
            this.newState = newState;
        }

        /** Deep compare two train state change */
        @SuppressFBWarnings({"BC_UNCONFIRMED_CAST"})
        public boolean deepEquals(TrainStateChange o) {
            if (!o.newState.deepEquals(o.newState))
                return false;
            if (!DeepEqualsUtils.deepEquals(positionUpdates, o.positionUpdates))
                return false;
            return speedDirectivesUpdates.equals(o.speedDirectivesUpdates);
        }

        public static final class PathValue<T> {
            public final double pathPosition;
            public final T value;

            public PathValue(double pathPosition, T value) {
                this.pathPosition = pathPosition;
                this.value = value;
            }

            @Override
            public int hashCode() {
                return value.hashCode() * 31 + Double.hashCode(pathPosition);
            }

            @Override
            @SuppressFBWarnings({"FE_FLOATING_POINT_EQUALITY"})
            public boolean equals(Object obj) {
                if (obj == null || obj.getClass() != PathValue.class)
                    return false;
                var o = (PathValue<?>) obj;
                return o.pathPosition == pathPosition && o.value.equals(value);
            }
        }

        public static final class SpeedUpdate implements DeepComparable<SpeedUpdate> {
            public double pathPosition;
            public double time;
            public double speed;

            /**
             * A speed update
             * @param pathPosition the current position
             * @param time the current time
             * @param speed the current speed
             */
            public SpeedUpdate(double pathPosition, double time, double speed) {
                this.pathPosition = pathPosition;
                this.time = time;
                this.speed = speed;
            }

            public double interpolatePosition(double nextTime) {
                double delta = nextTime - time;
                return pathPosition + delta * speed;
            }

            @Override
            @SuppressFBWarnings({"FE_FLOATING_POINT_EQUALITY"})
            public boolean deepEquals(SpeedUpdate other) {
                return pathPosition == other.pathPosition && time == other.time && speed == other.speed;
            }
        }

        public static final class PathUpdates<T> extends CryoList<PathValue<T>> {
            private static final long serialVersionUID = -398512329955860429L;

            /**
             * Add an update, avoiding duplicates
             * @param pathPosition the position on the path
             * @param value the value at the given position
             */
            public void dedupAdd(double pathPosition, T value) {
                if (isEmpty()) {
                    add(new PathValue<>(pathPosition, value));
                    return;
                }

                var last = get(size() - 1);

                // only add the new value if it differs from the last one
                if (!last.value.equals(value))
                    add(new PathValue<>(pathPosition, value));
            }
        }

        public static final class SpeedUpdates extends CryoList<SpeedUpdate> {
            private static final long serialVersionUID = 1186037080779235871L;

            /**
             * Adds a speed update, avoiding duplicates
             * @param pathPosition the new position on the path
             * @param time the current time
             * @param speed the current speed
             */
            @SuppressFBWarnings({"FE_FLOATING_POINT_EQUALITY"})
            public void addSpeedUpdate(double pathPosition, double time, double speed) {
                if (isEmpty()) {
                    add(new SpeedUpdate(pathPosition, time, speed));
                    return;
                }

                var last = get(size() - 1);
                if (last.speed == speed)
                    return;

                add(new SpeedUpdate(pathPosition, time, speed));
            }
        }

        /**
         * Finds the last speed update at a given time
         * @param time the reference time
         * @return the last speed update at a given time
         */
        public SpeedUpdate findLastSpeedUpdate(double time) {
            SpeedUpdate lastUpdate = null;

            for (var update : positionUpdates) {
                if (update.time > time)
                    break;
                lastUpdate = update;
            }
            return lastUpdate;
        }

        @Override
        public final Void apply(Simulation sim, Train train) {
            train.lastState = this.newState;
            return null;
        }

        @Override
        public Train getEntity(Simulation sim) {
            return sim.trains.get(trainID);
        }

        @Override
        public String toString() {
            return String.format(
                    "TrainStateChange { speed=%.2f, newState.headPathPosition=%.2f }",
                    newState.speed,
                    newState.location.getPathPosition()
            );
        }
    }
    // endregion
}
