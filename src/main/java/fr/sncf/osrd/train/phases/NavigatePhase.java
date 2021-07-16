package fr.sncf.osrd.train.phases;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import fr.sncf.osrd.infra.StopActionPoint;
import fr.sncf.osrd.infra.signaling.ActionPoint;
import fr.sncf.osrd.infra.trackgraph.TrackSection;
import fr.sncf.osrd.simulation.Change;
import fr.sncf.osrd.simulation.Simulation;
import fr.sncf.osrd.train.Interaction;
import fr.sncf.osrd.train.InteractionType;
import fr.sncf.osrd.train.InteractionTypeSet;
import fr.sncf.osrd.train.TrackSectionRange;
import fr.sncf.osrd.train.Train;
import fr.sncf.osrd.train.TrainPath;
import fr.sncf.osrd.train.TrainStop;
import fr.sncf.osrd.utils.TrackSectionLocation;

public abstract class NavigatePhase implements Phase {
    public final TrainPath expectedPath;
    public TrackSectionLocation startLocation;
    public final TrackSectionLocation endLocation;
    protected final ArrayList<Interaction> interactionsPath;
    protected final Interaction lastInteractionOnPhase;

    protected NavigatePhase(TrackSectionLocation startLocation, TrackSectionLocation endLocation,
            ArrayList<Interaction> interactionsPath, TrainPath expectedPath) {
        this.startLocation = startLocation;
        this.endLocation = endLocation;
        this.interactionsPath = interactionsPath;
        this.expectedPath = expectedPath;
        lastInteractionOnPhase = interactionsPath.get(interactionsPath.size() - 1);
    }

    protected static void addStopInteractions(ArrayList<Interaction> interactions, List<TrainStop> stops) {
        for (int i = 0; i < stops.size(); i++) {
            var stop = stops.get(i);
            interactions.add(new Interaction(InteractionType.HEAD, stop.position, new StopActionPoint(i)));
        }
        interactions.sort(Comparator.comparingDouble(x -> x.position));
    }

    protected static ArrayList<Interaction> trackSectionToActionPointPath(double driverSightDistance, TrainPath path,
            TrackSectionLocation startLocation, TrackSectionLocation endLocation,
            Iterable<TrackSectionRange> trackSectionRanges) {
        var startPosition = path.convertTrackLocation(startLocation);
        var endPosition = path.convertTrackLocation(endLocation);
        var eventPath = new ArrayList<Interaction>();
        double pathLength = 0;
        for (var trackRange : trackSectionRanges) {
            if (pathLength + trackRange.length() >= startPosition)
                registerRange(eventPath, trackRange, pathLength, driverSightDistance);
            pathLength += trackRange.length();
            if (pathLength > endPosition + driverSightDistance)
                break;
        }

        // create a map switch index -> switch id
        var nodeSwitch = new HashMap<Integer, String>();
        for (var route : path.routePath) {
            for (var s : route.switchesPosition.keySet()) {
                nodeSwitch.put(s.index, s.id);
            }
        }

        // add the switch to interactions
        double dist = 0;
        String lastSwitchEncounteredId = null;
        for (var trackRange : trackSectionRanges) {
            String switchBeginId = null;
            if (Math.abs(trackRange.getBeginPosition() - 0) < 1e-6) {
                switchBeginId = nodeSwitch.getOrDefault(trackRange.edge.startNode, null);
            }
            if (Math.abs(trackRange.getBeginPosition() - trackRange.edge.length) < 1e-6) {
                switchBeginId = nodeSwitch.getOrDefault(trackRange.edge.endNode, null);
            }
            String switchEndId = null;
            if (Math.abs(trackRange.getEndPosition() - 0) < 1e-6) {
                switchEndId = nodeSwitch.getOrDefault(trackRange.edge.startNode, null);
            }
            if (Math.abs(trackRange.getEndPosition() - trackRange.edge.length) < 1e-6) {
                switchEndId = nodeSwitch.getOrDefault(trackRange.edge.endNode, null);
            }

            if (switchBeginId != null && !switchBeginId.equals(lastSwitchEncounteredId)) {
                var passage = new SwitchActionPoint(switchBeginId);
                eventPath.add(new Interaction(InteractionType.HEAD, dist, passage));
            }
            dist += trackRange.length();
            if (switchEndId != null) {
                var passage = new SwitchActionPoint(switchEndId);
                eventPath.add(new Interaction(InteractionType.HEAD, dist, passage));
            }
            lastSwitchEncounteredId = switchEndId;
        }

        eventPath = eventPath.stream()
                .filter(interaction -> interaction.position >= startPosition && interaction.position <= endPosition)
                .sorted().collect(Collectors.toCollection(ArrayList::new));

        return eventPath;
    }

    private static void registerRange(ArrayList<Interaction> eventPath, TrackSectionRange trackRange, double pathLength,
            double driverSightDistance) {
        for (var interactablePoint : TrackSection.getInteractables(trackRange.edge, trackRange.direction)) {
            if (!trackRange.containsPosition(interactablePoint.position))
                continue;

            var interactable = interactablePoint.value;
            var edgeDistToObj = Math.abs(interactablePoint.position - trackRange.getBeginPosition());

            if (interactable.getInteractionsType().interactWithHead()) {
                var distance = pathLength + edgeDistToObj;
                eventPath.add(new Interaction(InteractionType.HEAD, distance, interactable));
            }
            if (interactable.getInteractionsType().interactWhenSeen()) {
                var sightDistance = Double.min(interactable.getActionDistance(), driverSightDistance);
                var distance = pathLength + edgeDistToObj - sightDistance;
                if (distance < 0)
                    distance = 0;
                eventPath.add(new Interaction(InteractionType.SEEN, distance, interactable));
            }
        }
    }

    @Override
    public TrackSectionLocation getEndLocation() {
        return endLocation;
    }

    /** This class represent the location of a switch */
    public static final class SwitchActionPoint implements ActionPoint {

        private final String switchId;

        public SwitchActionPoint(String switchId) {
            super();
            this.switchId = switchId;
        }

        @Override
        public InteractionTypeSet getInteractionsType() {
            return new InteractionTypeSet();
        }

        @Override
        public double getActionDistance() {
            return 0;
        }

        @Override
        public void interact(Simulation sim, Train train, InteractionType actionType) {
            var change = new PassageOnSwitch(sim, train.getName(), switchId);
            sim.publishChange(change);
        }

        @Override
        public String toString() {
            return "SwitchActionPoint { }";
        }

        public static class PassageOnSwitch extends Change {
            public final String trainId;
            public final String switchId;

            public PassageOnSwitch(Simulation sim, String trainId, String switchId) {
                super(sim);
                this.trainId = trainId;
                this.switchId = switchId;
            }

            @Override
            public void replay(Simulation sim) {
            }

            @Override
            public String toString() {
                return String.format("PassageOnSwitch { train: %s, switch: %s }", trainId, switchId);
            }
        }
    }
}