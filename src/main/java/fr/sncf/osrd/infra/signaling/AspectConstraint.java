package fr.sncf.osrd.infra.signaling;

import fr.sncf.osrd.train.Interaction;
import fr.sncf.osrd.train.InteractionType;
import fr.sncf.osrd.train.TrainState;
import fr.sncf.osrd.train.phases.NavigatePhaseState;
import fr.sncf.osrd.train.phases.SignalNavigatePhase;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

public abstract class AspectConstraint {

    public static class ConstraintPosition {
        public final double offset;
        public final ConstraintPosition.Element element;

        public enum Element {
            CURRENT_SIGNAL,
            NEXT_SIGNAL,
            END
        }

        public ConstraintPosition(double offset, ConstraintPosition.Element element) {
            this.offset = offset;
            this.element = element;
        }

        /** Convert to a path position given a phase a train state */
        public double convert(NavigatePhaseState navigatePhase, TrainState trainState) {
            Predicate<Interaction> isSignal = interaction -> interaction.interactionType == InteractionType.HEAD
                    && interaction.actionPoint.getClass() == Signal.class;
            Interaction interactionElement = null;
            if (element == Element.NEXT_SIGNAL) {
                AtomicBoolean isNext = new AtomicBoolean(false);
                interactionElement = navigatePhase.findFirstInteractions(trainState, interaction -> {
                    if (!isSignal.test(interaction))
                        return false;
                    if (isNext.get())
                        return true;
                    isNext.set(true);
                    return false;
                });
            } else if (element == Element.CURRENT_SIGNAL) {
                interactionElement = navigatePhase.findFirstInteractions(trainState, isSignal);
            }

            if (interactionElement == null)
                return Double.POSITIVE_INFINITY;

            return interactionElement.position + offset;
        }
    }

    public static class SpeedLimit extends AspectConstraint {
        public final double speed;
        public final ConstraintPosition appliesAt;
        public final ConstraintPosition until;

        /** Create a speed limit constraint */
        public SpeedLimit(double speed, ConstraintPosition appliesAt, ConstraintPosition until) {
            this.speed = speed;
            this.appliesAt = appliesAt;
            this.until = until;
        }
    }
}
