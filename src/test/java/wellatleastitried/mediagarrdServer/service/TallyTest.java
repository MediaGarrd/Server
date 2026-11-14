package wellatleastitried.mediagarrdServer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.FAILED;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.PARTIAL;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import wellatleastitried.mediagarrdServer.model.BackupServiceResult;
import wellatleastitried.mediagarrdServer.service.ServiceBackupRunner.PathCopy;
import wellatleastitried.mediagarrdServer.service.ServiceBackupRunner.ServiceRound;
import wellatleastitried.mediagarrdServer.service.ServiceBackupRunner.Tally;

class TallyTest {

    private static ServiceRound failed(String error, String... paths) {
        return new ServiceRound(error, java.util.Arrays.stream(paths).map(p -> new PathCopy(0, p, false, null, Set.of())).toList());
    }

    @Test
    void theSameServiceLevelErrorInSeveralRoundsIsReportedOnce() {
        Tally tally = new Tally("media");
        tally.add(failed("no helper", "/a"));
        tally.add(failed("no helper", "/b"));

        BackupServiceResult result = tally.result();

        assertEquals(FAILED, result.status());
        assertEquals("no helper", result.errorMessage());
    }

    @Test
    void differentErrorsAreAllKeptInOrder() {
        Tally tally = new Tally("media");
        tally.add(failed("no helper", "/a"));
        tally.add(new ServiceRound(null, List.of(new PathCopy(1, "/b", false, "/b: boom", Set.of()), new PathCopy(2, "/c", true, null, Set.of()))));
        tally.add(failed("other", "/d"));

        BackupServiceResult result = tally.result();

        assertEquals(PARTIAL, result.status());
        assertEquals("no helper; /b: boom; other", result.errorMessage());
    }
}
