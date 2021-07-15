package fr.sncf.osrd.speedcontroller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * The speed controller used to slow down the train from the announce of a speed limit up to its enforcement signal.
 */
public final class LimitAnnounceSpeedController extends SpeedController {
    public final double targetSpeedLimit;
    public final double gamma;

    /** Creates a speed controller meant to slow down the train before a speed limit. */
    public LimitAnnounceSpeedController(
            double targetSpeedLimit,
            double startPosition,
            double endPosition,
            double gamma
    ) {
        super(startPosition, endPosition);
        this.targetSpeedLimit = targetSpeedLimit;
        this.gamma = gamma;
    }

    /** Create LimitannouceSpeedController from initial speed and target position */
    public static LimitAnnounceSpeedController create(
            double initialSpeed,
            double targetSpeed,
            double targetPosition,
            double gamma
    ) {
        var requiredBrakingDistance = (initialSpeed * initialSpeed - targetSpeed * targetSpeed) / (2 * gamma);
        return new LimitAnnounceSpeedController(
                targetSpeed,
                targetPosition - requiredBrakingDistance,
                targetPosition,
                gamma
        );
    }

    /** Create LimitannouceSpeedController from initial speed and initial position */
    public static LimitAnnounceSpeedController createFromInitialPosition(
            double initialSpeed,
            double targetSpeed,
            double initialPosition,
            double gamma
    ) {
        var requiredBrakingDistance = (initialSpeed * initialSpeed - targetSpeed * targetSpeed) / 2 * gamma;
        return new LimitAnnounceSpeedController(
                targetSpeed,
                initialPosition,
                initialPosition + requiredBrakingDistance,
                gamma
        );
    }

    @Override
    public SpeedDirective getDirective(
            double pathPosition
    ) {
        var distance = endPosition - pathPosition;
        assert distance >= 0;
        var currentLimit = Math.sqrt(targetSpeedLimit * targetSpeedLimit + 2 * distance * gamma);
        return new SpeedDirective(currentLimit);
    }

    @Override
    public SpeedController scaled(double scalingFactor) {
        return new LimitAnnounceSpeedController(targetSpeedLimit * scalingFactor,
                beginPosition, endPosition, gamma);
    }

    @Override
    public String toString() {
        return String.format(
                "LimitAnnounceSpeedController { targetSpeed=%.3f, begin=%.3f, end=%.3f, gamma=%.3f }",
                targetSpeedLimit, beginPosition, endPosition, gamma
        );
    }

    @Override
    @SuppressFBWarnings({"BC_UNCONFIRMED_CAST", "FE_FLOATING_POINT_EQUALITY"})
    public boolean deepEquals(SpeedController other) {
        if (!equalRange(other))
            return false;
        if (other.getClass() != LimitAnnounceSpeedController.class)
            return false;
        var o = (LimitAnnounceSpeedController) other;
        return o.targetSpeedLimit == targetSpeedLimit && o.gamma == gamma;
    }
}
