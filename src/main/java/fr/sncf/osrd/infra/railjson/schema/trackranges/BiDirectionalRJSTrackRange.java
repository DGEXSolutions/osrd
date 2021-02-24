package fr.sncf.osrd.infra.railjson.schema.trackranges;

import fr.sncf.osrd.utils.graph.ApplicableDirections;

public class BiDirectionalRJSTrackRange extends RJSTrackRange {
    BiDirectionalRJSTrackRange(double start, double end) {
        super(start, end);
    }

    @Override
    public ApplicableDirections getNavigability() {
        return ApplicableDirections.BOTH;
    }
}