package fr.sncf.osrd;

import static fr.sncf.osrd.Helpers.*;
import static org.junit.jupiter.api.Assertions.fail;

import fr.sncf.osrd.config.Config;
import fr.sncf.osrd.infra.InvalidInfraException;
import fr.sncf.osrd.railjson.parser.exceptions.InvalidRollingStock;
import fr.sncf.osrd.railjson.parser.exceptions.InvalidSchedule;
import fr.sncf.osrd.railjson.parser.exceptions.InvalidSuccession;
import fr.sncf.osrd.simulation.ChangeReplayChecker;
import fr.sncf.osrd.simulation.ChangeSerializer;
import fr.sncf.osrd.simulation.Simulation;
import fr.sncf.osrd.simulation.SimulationError;
import fr.sncf.osrd.simulation.changelog.ArrayChangeLog;
import fr.sncf.osrd.simulation.changelog.ChangeConsumer;
import fr.sncf.osrd.simulation.changelog.ChangeConsumerMultiplexer;
import fr.sncf.osrd.simulation.changelog.ChangeLogSummarizer;
import fr.sncf.osrd.train.events.TrainCreatedEvent;
import okio.Buffer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;

public class RunExamplesTest {
    private static void runGivenConfigInfra(String path) {
        try {
            Config config = Config.readFromFile(getResourcePath(path));

            var changeConsumers = new ArrayList<ChangeConsumer>();
            var changelog = new ArrayChangeLog();
            changeConsumers.add(changelog);

            var multiplexer = new ChangeConsumerMultiplexer(changeConsumers);
            var sim = Simulation.createFromInfraAndSuccessions(config.infra,
                    config.switchSuccessions, 0, multiplexer);

            multiplexer.add(ChangeReplayChecker.from(sim));

            for (var trainSchedule : config.trainSchedules)
                TrainCreatedEvent.plan(sim, trainSchedule);

            while (!sim.isSimulationOver())
                sim.step();

            ChangeLogSummarizer.summarize(changelog);

            ChangeSerializer.serializeChangeLog(changelog, new Buffer());
        } catch (InvalidRollingStock | IOException | SimulationError
                | InvalidInfraException | InvalidSchedule | InvalidSuccession e) {
            fail(e);
        }
    }

    @Test
    public void testTinyInfra() {
        runGivenConfigInfra("tiny_infra/config_railjson.json");
    }

    @Test
    public void testCircularInfra() {
        runGivenConfigInfra("circular_infra/config.json");
    }
}
