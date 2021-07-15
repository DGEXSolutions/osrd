package fr.sncf.osrd.train;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import fr.sncf.osrd.infra.Infra;
import fr.sncf.osrd.infra.trackgraph.Switch;
import fr.sncf.osrd.infra.trackgraph.TrackSection;
import fr.sncf.osrd.infra_state.InfraState;
import fr.sncf.osrd.utils.DeepComparable;
import fr.sncf.osrd.utils.DeepEqualsUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class TrainPositionTracker implements Cloneable, DeepComparable<TrainPositionTracker> {
    private final transient Infra infra;
    private final transient InfraState infraState;

    /** Distance covered since the train has departed */
    private double pathPosition = 0;

    /** The list of edges the train currently spans over. */
    public final ArrayDeque<TrackSectionRange> trackSectionRanges;

    /** The path of the train */
    public final List<TrackSectionRange> trackSectionPath;

    /** If set to true, we follow the given path rather than the switch positions. Used for margin computations */
    public boolean ignoreInfraState = false;

    /**
     * Create a new position tracker on some given infrastructure and path.
     * @param infraState the infrastructure to navigate on
     */
    public TrainPositionTracker(
            Infra infra,
            InfraState infraState,
            ArrayDeque<TrackSectionRange> trackSectionRanges,
            List<TrackSectionRange> trackSectionPath
    ) {
        this.infra = infra;
        this.infraState = infraState;
        this.trackSectionRanges = trackSectionRanges;
        this.trackSectionPath = trackSectionPath;
    }

    private TrainPositionTracker(TrainPositionTracker tracker) {
        this.infra = tracker.infra;
        this.infraState = tracker.infraState;
        this.trackSectionRanges = tracker.trackSectionRanges.clone();
        this.pathPosition = tracker.pathPosition;
        this.trackSectionPath = new ArrayList<>(tracker.trackSectionPath);
        this.ignoreInfraState = tracker.ignoreInfraState;
    }

    // region STD_OVERRIDES

    @Override
    @SuppressFBWarnings({"FE_FLOATING_POINT_EQUALITY"})
    public boolean equals(Object obj) {
        if (obj == null)
            return false;

        if (obj.getClass() != TrainPositionTracker.class)
            return false;

        var other = (TrainPositionTracker) obj;
        return trackSectionRanges.equals(other.trackSectionRanges);
    }

    @Override
    public int hashCode() {
        return Objects.hash(trackSectionRanges);
    }
    // endregion

    /**
     * Makes a copy of the position tracker.
     * @return a copy of the position tracker.
     */
    @Override
    public TrainPositionTracker clone() {
        return new TrainPositionTracker(this);
    }

    private TrackSectionRange nextTrackSectionPosition(double delta) {
        var curTrackSectionPos = trackSectionRanges.getFirst();
        var neighbors = infra.trackGraph.getEndNeighborRels(curTrackSectionPos.edge, curTrackSectionPos.direction);

        if (neighbors.isEmpty())
            throw new RuntimeException("Couldn't find a next track section");

        var next = neighbors.get(0);
        var nextTrackSection = next.getEdge(curTrackSectionPos.edge, curTrackSectionPos.direction);

        // In case of a switch, we need to get the next track section align with the position of the switch.
        if (neighbors.size() > 1) {
            if (ignoreInfraState) {
                // If we need to ignore the infra state, we refer to the given path instead
                nextTrackSection = null;
                var currentEdge = curTrackSectionPos.edge;
                for (int i = 1; i < trackSectionPath.size(); i++) {
                    if (trackSectionPath.get(i - 1).edge.id.equals(currentEdge.id)
                            && !trackSectionPath.get(i).edge.id.equals(currentEdge.id)) {
                        nextTrackSection = trackSectionPath.get(i).edge;
                        break;
                    }
                }
                if (nextTrackSection == null)
                    throw new RuntimeException("Can't move train further because it has reached the end of its path");
            } else {
                var nodeIndex = curTrackSectionPos.edge.getEndNode(curTrackSectionPos.direction);
                var node = infra.trackGraph.getNode(nodeIndex);
                assert node.getClass() == Switch.class;
                var switchState = infraState.getSwitchState(((Switch) node).switchIndex);
                nextTrackSection = switchState.getBranch();
                if (nextTrackSection == null)
                    throw new RuntimeException("Can't move the train further because a switch is in motion.");
            }
        }

        var nextTrackSectionDirection = nextTrackSection.getDirection(
                curTrackSectionPos.edge, curTrackSectionPos.direction);
        return TrackSectionRange.makeNext(nextTrackSection, nextTrackSectionDirection, delta);
    }

    /**
     * Updates the position of the train on the network.
     * @param positionDelta How much the train moves by.
     */
    public void updatePosition(double expectedTrainLength, double positionDelta) {
        updateHeadPosition(positionDelta);
        pathPosition += positionDelta;

        double currentTrainLength = 0;
        for (var section : trackSectionRanges)
            currentTrainLength += section.length();

        var tailDisplacement = currentTrainLength - expectedTrainLength;
        if (Math.abs(tailDisplacement) > 1e-3)
            updateTailPosition(tailDisplacement);
    }

    /** TODO: Check if it's the wanted behavior...
     * TODO: should be void
     * Move the head of train to positionDelta ahead.
     * The train stop if it can't go further.
     */
    private void updateHeadPosition(double targetDist) {
        var remainingDist = targetDist;
        var headPos = trackSectionRanges.getFirst();
        var edgeSpaceAhead = headPos.forwardSpace();
        var edgeMovement = Double.min(targetDist, edgeSpaceAhead);
        headPos.expandForward(edgeMovement);
        remainingDist -= edgeMovement;

        // add edges to the current edges queue as the train moves forward
        while (remainingDist > 0) {
            var nextPos = nextTrackSectionPosition(remainingDist);
            // this should kind of be nextPos.length(), but doing it this way avoids float compare errors
            remainingDist -= nextPos.edge.length;
            trackSectionRanges.addFirst(nextPos);
        }
    }

    private void updateTailPosition(double positionDelta) {
        while (true) {
            var tailPos = trackSectionRanges.getLast();
            var availableSpace = tailPos.length();
            if (availableSpace > positionDelta) {
                tailPos.shrinkForward(positionDelta);
                break;
            }
            positionDelta -= availableSpace;
            trackSectionRanges.removeLast();
        }
    }

    public double getPathPosition() {
        return pathPosition;
    }

    /** Computes the maximum grade (slope) under the train. */
    public double maxTrainGrade() {
        var val = 0.;
        for (var track : trackSectionRanges) {
            for (var slope : TrackSection.getSlope(track.edge, track.direction).data) {
                if (track.containsPosition(slope.position))
                    val = Double.max(slope.value, val);
            }
        }
        return val;
    }

    @Override
    @SuppressFBWarnings({"FE_FLOATING_POINT_EQUALITY"})
    public boolean deepEquals(TrainPositionTracker other) {
        return pathPosition == other.pathPosition
                && DeepEqualsUtils.deepEquals(trackSectionRanges, other.trackSectionRanges);
    }
}
