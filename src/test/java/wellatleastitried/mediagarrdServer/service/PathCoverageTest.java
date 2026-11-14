package wellatleastitried.mediagarrdServer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import wellatleastitried.mediagarrdServer.model.ServiceDefinition;
import wellatleastitried.mediagarrdServer.service.PathCoverage.Item;
import wellatleastitried.mediagarrdServer.service.PathCoverage.Owner;

class PathCoverageTest {

    private static Item item(String service, String path) {
        return new Item(new ServiceDefinition(service, List.of(path)), 0);
    }

    @Test
    void anAncestorOwnsEveryDescendantAndSiblingsStaySeparate() {
        Item a = item("a", "/srv/a");
        Item inner = item("b", "/srv/a/b/c");
        Item middle = item("c", "/srv/a/b");
        Item sibling = item("d", "/srv/ab");

        List<Owner> owners = PathCoverage.plan(List.of(inner, a, sibling, middle));

        assertEquals(List.of(a, sibling), owners.stream().map(Owner::item).toList());
        assertEquals(List.of(inner, middle), owners.get(0).covered());
        assertEquals(List.of(), owners.get(1).covered());
    }

    @Test
    void equalPathsAreOwnedByTheServiceThatSortsFirst() {
        Item b = item("b", "/srv/x");
        Item a = item("a", "/srv/x");

        List<Owner> owners = PathCoverage.plan(List.of(b, a));

        assertEquals(List.of(a), owners.stream().map(Owner::item).toList());
        assertEquals(List.of(b), owners.get(0).covered());
    }

    @Test
    void keysAreEntryNamesWithoutTheLeadingSlash() {
        assertEquals("srv/a/b", item("a", "/srv/a/b").key());
    }
}
