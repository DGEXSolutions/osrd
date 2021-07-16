package fr.sncf.osrd.cbtc;

import java.util.ArrayList;

import fr.sncf.osrd.simulation.Simulation;
import fr.sncf.osrd.speedcontroller.SpeedController;
import fr.sncf.osrd.train.TrainSchedule;
import fr.sncf.osrd.train.TrainState;

public class CBTCATP {
    private Simulation sim;
    private TrainSchedule trainSchedule;
    private TrainState trainState;

    public CBTCATP(Simulation sim, TrainSchedule trainSchedule, TrainState trainState) {
        this.trainSchedule = trainSchedule;
        this.trainState = trainState;
        this.sim = sim;
    }

    public ArrayList<SpeedController> directive() {
        return new ArrayList<SpeedController>(); 
    }
}