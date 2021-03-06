package fr.sncf.osrd.railjson.schema.infra;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import fr.sncf.osrd.utils.graph.ApplicableDirection;

/** This class represents a link between two track sections */
@SuppressFBWarnings({"URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD"})
public class RJSTrackSectionLink {
    /** The navigability between the two track sections. In most cases it's BOTH way. */
    public ApplicableDirection navigability;
    public RJSTrackSection.EndpointID begin;
    public RJSTrackSection.EndpointID end;

    /**
     * Create a serialized track section link
     * @param navigability how this link can be used
     * @param begin the beginning of the link
     * @param end end end of the link
     */
    public RJSTrackSectionLink(
            ApplicableDirection navigability,
            RJSTrackSection.EndpointID begin,
            RJSTrackSection.EndpointID end
    ) {
        this.navigability = navigability;
        this.begin = begin;
        this.end = end;
    }
}
