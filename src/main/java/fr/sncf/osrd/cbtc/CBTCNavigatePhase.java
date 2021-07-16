package fr.sncf.osrd.cbtc;

import java.util.ArrayList;
import java.util.List;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import fr.sncf.osrd.simulation.Simulation;
import fr.sncf.osrd.simulation.SimulationError;
import fr.sncf.osrd.simulation.TimelineEvent;
import fr.sncf.osrd.speedcontroller.SpeedController;
import fr.sncf.osrd.train.Interaction;
import fr.sncf.osrd.train.Train;
import fr.sncf.osrd.train.TrainPath;
import fr.sncf.osrd.train.TrainSchedule;
import fr.sncf.osrd.train.TrainState;
import fr.sncf.osrd.train.TrainStatus;
import fr.sncf.osrd.train.TrainStop;
import fr.sncf.osrd.train.events.TrainMoveEvent;
import fr.sncf.osrd.train.events.TrainReachesActionPoint;
import fr.sncf.osrd.train.phases.NavigatePhase;
import fr.sncf.osrd.train.phases.NavigatePhaseState;
import fr.sncf.osrd.train.phases.PhaseState;
import fr.sncf.osrd.utils.TrackSectionLocation;

/**
 * Navigation phase to be used when the train is CBTC
 */
public final class CBTCNavigatePhase extends NavigatePhase {

    /**
     * Creates a CBTC navigation phase with an already determined path and interaction path.
     * @param startLocation
     * @param endLocation
     * @param interactionsPath
     * @param expectedPath
     */
    private CBTCNavigatePhase(TrackSectionLocation startLocation, TrackSectionLocation endLocation,
            ArrayList<Interaction> interactionsPath, TrainPath expectedPath) {
        super(startLocation, endLocation, interactionsPath, expectedPath);
    }

    /**
     * Creates a new CBTC navigation phase from an already determined path
     * @param driverSightDistance
     * @param startLocation
     * @param endLocation
     * @param expectedPath
     * @param stops
     * 
     * @return The new CBTC navigation phase.
     */
    public static CBTCNavigatePhase from(double driverSightDistance, TrackSectionLocation startLocation,
            TrackSectionLocation endLocation, TrainPath expectedPath, List<TrainStop> stops) {
        if (stops == null)
            stops = new ArrayList<>();

        var actionPointPath = trackSectionToActionPointPath(driverSightDistance, expectedPath, startLocation,
                endLocation, expectedPath.trackSectionPath);
        addStopInteractions(actionPointPath, stops);
        return new CBTCNavigatePhase(startLocation, endLocation, actionPointPath, expectedPath);
    }

    @Override
    public PhaseState getState(Simulation sim, TrainSchedule schedule) {
        return new CBTCNavigatePhaseState(this, sim, schedule);
    }

    /**
     * State of a CBTC phase.
     */
    public static final class CBTCNavigatePhaseState extends NavigatePhaseState {
        @Override
        @SuppressFBWarnings({"BC_UNCONFIRMED_CAST"})
        public boolean deepEquals(PhaseState other) {
            if (other.getClass() != CBTCNavigatePhaseState.class)
                return false;
            var o = (CBTCNavigatePhaseState) other;
            return o.phase == phase && o.interactionsPathIndex == interactionsPathIndex;
        }

        @Override
        public PhaseState clone() {
            return new CBTCNavigatePhaseState(this);
        }

        /**
         * Create a new state related to the given phase.
         * @param phase
         * @param sim
         * @param schedule
         */
        CBTCNavigatePhaseState(CBTCNavigatePhase phase, Simulation sim, TrainSchedule schedule) {
            super(phase, sim, schedule);
        }

        /**
         * Create a clone of the given state.
         * @param state
         */
        CBTCNavigatePhaseState(CBTCNavigatePhaseState state) {
            super(state);
        }

        @Override
        public TimelineEvent simulate(Simulation sim, Train train, TrainState trainState) throws SimulationError {
            // Check if we reached our goal
            if (hasPhaseEnded()) {
                var nextState = trainState.nextPhase(sim);
                var change = new Train.TrainStateChange(sim, train.getName(), nextState);
                change.apply(sim, train);
                sim.publishChange(change);
                if (trainState.isDuringLastPhase())
                    return null;
                else
                    return nextState.simulatePhase(train, sim);
            }

            // The time of the next CBTC position update (the 1e-9 is here to prevent from double division error)
            // TODO : find a better way to do this
            double nextTime = 0.2 * (Math.floor(sim.getTime() / 0.2 + 1e-9) + 1);

            // 1) find the next interaction event
            var nextInteraction = peekInteraction(trainState);

            // 2) If the action point can interact with the tail of the train add it to the interaction list
            addInteractionUnderTrain(trainState, nextInteraction);

            // 3) simulate up to nextEventTrackPosition
            var simulationResult = trainState.evolveStateUntilTimeOrPosition(sim, nextTime, nextInteraction.position);

            // 4) create an event with simulation data up to this point

            // The train reached the action point
            if (trainState.location.getPathPosition() >= nextInteraction.position) {
                popInteraction(trainState);
                return TrainReachesActionPoint.plan(sim, trainState.time, train, simulationResult, nextInteraction);
            }

            // The train didn't reached the action point (stopped because of signalisation)
            var event = TrainMoveEvent.plan(sim, trainState.time, train, simulationResult);

            if(train.getLastState().status != TrainStatus.REACHED_DESTINATION && train.getLastState().status != TrainStatus.STOP){
                CBTCEvent.plan(sim, nextTime, train);
            }

            return event;
        }

        /**
         * Return the list of speed controllers related to the phase. 
         */        
        @Override
        public ArrayList<SpeedController> getSpeedControllers() {
            var controllers = new ArrayList<SpeedController>();
            for (var signalControllers : signalControllers.values())
                controllers.addAll(signalControllers);

            TrainState trainState = sim.trains.get(schedule.trainID).getLastState();
            if (trainState != null) {
                CBTCATP atp = new CBTCATP(sim, schedule, trainState);
                controllers.addAll(atp.directive());
            }

            return controllers;
        }
    }
}