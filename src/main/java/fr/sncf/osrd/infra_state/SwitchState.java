package fr.sncf.osrd.infra_state;

import static fr.sncf.osrd.infra.trackgraph.SwitchPosition.LEFT;
import static fr.sncf.osrd.infra.trackgraph.SwitchPosition.MOVING;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import fr.sncf.osrd.infra.railscript.value.RSMatchable;
import fr.sncf.osrd.infra.railscript.value.RSValue;
import fr.sncf.osrd.infra.trackgraph.Switch;
import fr.sncf.osrd.infra.trackgraph.SwitchPosition;
import fr.sncf.osrd.infra.trackgraph.TrackSection;
import fr.sncf.osrd.infra_state.events.SwitchMoveEvent;
import fr.sncf.osrd.simulation.EntityChange;
import fr.sncf.osrd.simulation.Simulation;
import fr.sncf.osrd.simulation.SimulationError;

/**
 * The state of the route is the actual entity which interacts with the rest of the infrastructure
 */
public final class SwitchState implements RSMatchable {
    public final Switch switchRef;

    private SwitchPosition position;

    public SwitchState(Switch switchRef) {
        this.switchRef = switchRef;
        this.position = LEFT;
    }

    public SwitchPosition getPosition() {
        return position;
    }

    /**
     * Return currently active branch
     */
    public TrackSection getBranch() {
        switch (position) {
            case LEFT:
                return switchRef.leftTrackSection;
            case RIGHT:
                return switchRef.rightTrackSection;
            default:
                return null;
        }
    }

    /**
     * Change position of the switch
     */
    public void setPosition(Simulation sim, SwitchPosition position) throws SimulationError {
        if (this.position != position) {
            var change = new SwitchPositionChange(sim, this, position);
            change.apply(sim, this);
            sim.publishChange(change);
            for (var signal : switchRef.signalSubscribers) {
                var signalState = sim.infraState.getSignalState(signal.index);
                signalState.notifyChange(sim);
            }
        }
    }


    /**
     * Starts a switch change that will happen after the switch's delay
     */
    public void requestPositionChange(
            Simulation sim,
            SwitchPosition position,
            RouteState requestingRoute
    ) throws SimulationError {
        if (this.position != position) {
            var delay = switchRef.positionChangeDelay;
            SwitchMoveEvent.plan(sim, sim.getTime() + delay, position, this, requestingRoute);
            setPosition(sim, MOVING);
        }
    }

    @Override
    public int getEnumValue() {
        return position.ordinal();
    }

    @Override
    @SuppressFBWarnings({"BC_UNCONFIRMED_CAST"})
    public boolean deepEquals(RSValue other) {
        if (other.getClass() != SwitchState.class)
            return false;
        var o = (SwitchState) other;
        return o.position == position && o.switchRef == switchRef;
    }

    public static final class SwitchPositionChange extends EntityChange<SwitchState, Void> {
        SwitchPosition position;
        public final int switchIndex;

        protected SwitchPositionChange(Simulation sim, SwitchState entity, SwitchPosition position) {
            super(sim);
            this.position = position;
            this.switchIndex = entity.switchRef.switchIndex;
        }

        @Override
        public Void apply(Simulation sim, SwitchState entity) {
            entity.position = position;
            return null;
        }

        @Override
        public SwitchState getEntity(Simulation sim) {
            return sim.infraState.getSwitchState(switchIndex);
        }

        @Override
        public String toString() {
            return String.format("SwitchPositionChange { switch: %d, position: %s }", switchIndex, position);
        }
    }
}
