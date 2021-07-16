package fr.sncf.osrd.train.phases;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.Predicate;

import fr.sncf.osrd.infra.signaling.AspectConstraint;
import fr.sncf.osrd.infra.signaling.Signal;
import fr.sncf.osrd.infra_state.SignalState;
import fr.sncf.osrd.simulation.Simulation;
import fr.sncf.osrd.speedcontroller.LimitAnnounceSpeedController;
import fr.sncf.osrd.speedcontroller.MaxSpeedController;
import fr.sncf.osrd.speedcontroller.SpeedController;
import fr.sncf.osrd.train.Interaction;
import fr.sncf.osrd.train.InteractionType;
import fr.sncf.osrd.train.TrainSchedule;
import fr.sncf.osrd.train.TrainState;

public abstract class NavigatePhaseState extends PhaseState {
    public final NavigatePhase phase;
    protected int interactionsPathIndex = 0;
    protected final transient Simulation sim;
    protected final transient TrainSchedule schedule;
    protected final transient HashMap<Signal, ArrayList<SpeedController>> signalControllers;

    protected NavigatePhaseState(NavigatePhase phase, Simulation sim, TrainSchedule schedule) {
        this.sim = sim;
        this.schedule = schedule;
        this.phase = phase;
        this.signalControllers = new HashMap<>();
    }

    protected NavigatePhaseState(NavigatePhaseState state) {
        this.phase = state.phase;
        this.interactionsPathIndex = state.interactionsPathIndex;
        this.signalControllers = state.signalControllers;
        this.schedule = state.schedule;
        this.sim = state.sim;
    }

    protected boolean isInteractionUnderTrain(TrainState trainState) {
        var nextFrontalInteraction = phase.interactionsPath.get(interactionsPathIndex);

        double nextBackInteractionDistance = Double.POSITIVE_INFINITY;
        if (!trainState.actionPointsUnderTrain.isEmpty())
            nextBackInteractionDistance = trainState.actionPointsUnderTrain.getFirst().position;

        return nextBackInteractionDistance < nextFrontalInteraction.position;
    }

    /** Return the first interaction that satisfies the predicate. */
    public Interaction findFirstInteractions(TrainState trainState, Predicate<Interaction> predicate) {
        var interactionIndex = interactionsPathIndex;
        for (var underTrain : trainState.actionPointsUnderTrain) {
            var posUnderTrain = underTrain.position;
            while (interactionIndex < phase.interactionsPath.size()
                    && phase.interactionsPath.get(interactionIndex).position < posUnderTrain) {
                var interaction = phase.interactionsPath.get(interactionIndex);
                if (predicate.test(interaction))
                    return interaction;
                interactionIndex++;
            }
            if (predicate.test(underTrain))
                return underTrain;
        }
        while (interactionIndex < phase.interactionsPath.size()) {
            var interaction = phase.interactionsPath.get(interactionIndex);
            if (predicate.test(interaction))
                return interaction;
            interactionIndex++;
        }
        return null;
    }

    protected Interaction peekInteraction(TrainState trainState) {
        // Interact with next action point under the train
        if (isInteractionUnderTrain(trainState))
            return trainState.actionPointsUnderTrain.peekFirst();

        // Interact with next action point in front of the train
        return phase.interactionsPath.get(interactionsPathIndex);
    }

    protected void popInteraction(TrainState trainState) {
        if (isInteractionUnderTrain(trainState))
            trainState.actionPointsUnderTrain.removeFirst();
        else
            interactionsPathIndex++;
    }

    protected boolean hasPhaseEnded() {
        if (interactionsPathIndex == phase.interactionsPath.size())
            return true;
        if (interactionsPathIndex == 0)
            return false;
        return phase.interactionsPath.get(interactionsPathIndex - 1) == phase.lastInteractionOnPhase;
    }

   protected static void addInteractionUnderTrain(TrainState trainState, Interaction interaction) {
        if (interaction.interactionType == InteractionType.TAIL)
            return;
        if (!interaction.actionPoint.getInteractionsType().interactWithTail())
            return;

        var trainLength = trainState.trainSchedule.rollingStock.length;
        var underTrainInteraction = new Interaction(
                InteractionType.TAIL,
                interaction.position + trainLength,
                interaction.actionPoint
        );
        trainState.actionPointsUnderTrain.addLast(underTrainInteraction);
    }

   protected ArrayList<SpeedController> parseAspectConstraint(AspectConstraint constraint, TrainState trainState) {
        if (constraint.getClass() == AspectConstraint.SpeedLimit.class) {
            var speedLimit = (AspectConstraint.SpeedLimit) constraint;
            var appliesAt = speedLimit.appliesAt.convert(this, trainState);
            var until = speedLimit.until.convert(this, trainState);
            var res = new ArrayList<SpeedController>();
            res.add(LimitAnnounceSpeedController.create(
                    trainState.trainSchedule.rollingStock.maxSpeed,
                    speedLimit.speed,
                    appliesAt,
                    trainState.trainSchedule.rollingStock.timetableGamma
            ));
            res.add(new MaxSpeedController(
                    speedLimit.speed,
                    appliesAt,
                    until
            ));
            return res;
        }
        throw new RuntimeException("AspectConstraint not handled");
    }

    /** Add or update aspects constraint of a signal */
    public void setAspectConstraints(SignalState signalState, TrainState trainState) {
        var controllers = new ArrayList<SpeedController>();
        for (var aspect : signalState.aspects) {
            for (var constraint : aspect.constraints)
                controllers.addAll(parseAspectConstraint(constraint, trainState));
        }
        signalControllers.put(signalState.signal, controllers);
    }
}