package fr.sncf.osrd.railjson.schema.infra.trackranges;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import fr.sncf.osrd.utils.graph.ApplicableDirection;

@SuppressFBWarnings({"URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD"})
public abstract class RJSTrackRange {
    /** Positions from the beginning of the RJSTrackSection */
    public double begin;
    public double end;

    /** What sides the object can be approached from */
    public abstract ApplicableDirection getNavigability();

    RJSTrackRange(double begin, double end) {
        this.begin = begin;
        this.end = end;
    }
}
