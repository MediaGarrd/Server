package wellatleastitried.mediagarrdServer.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.EOFException;
import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();
    private final Logger logger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    private Level previous;

    @BeforeEach
    void capture() {
        previous = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        events.start();
        logger.addAppender(events);
    }

    @AfterEach
    void release() {
        logger.detachAppender(events);
        logger.setLevel(previous);
    }

    private List<Level> levelsAfterCommit(Exception e) throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.getWriter().flush();
        response.flushBuffer();
        assertNull(handler.unexpected(e, response));
        return events.list.stream().map(ILoggingEvent::getLevel).toList();
    }

    @Test
    void aFailureAfterTheResponseStartedIsLoggedAsAWarning() throws IOException {
        assertEquals(List.of(Level.WARN), levelsAfterCommit(new IOException("disk read failed")));
    }

    @Test
    void aClientThatWentAwayIsOnlyLoggedAtDebug() throws IOException {
        assertEquals(List.of(Level.DEBUG), levelsAfterCommit(new IOException("Broken pipe", new EOFException())));
        events.list.clear();
        assertEquals(List.of(Level.DEBUG), levelsAfterCommit(new AsyncRequestNotUsableException("gone")));
    }
}
