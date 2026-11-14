package wellatleastitried.mediagarrdServer.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import wellatleastitried.mediagarrdServer.model.ServiceDefinition;

final class PathCoverage {

    private static final Comparator<Item> TIE_BREAK = Comparator.comparing((Item item) -> item.service().name()).thenComparingInt(Item::index);

    record Item(ServiceDefinition service, int index) {

        String path() {
            return service.paths().get(index);
        }

        Path location() {
            return Path.of(path());
        }

        String key() {
            Path location = location();
            return location.getRoot() == null ? path() : location.getRoot().relativize(location).toString();
        }
    }

    record Owner(Item item, List<Item> covered) {}

    private PathCoverage() {}

    static List<Owner> plan(List<Item> unresolved) {
        List<Owner> owners = new ArrayList<>();
        for (Item candidate : unresolved) {
            if (unresolved.stream().noneMatch(other -> other != candidate && covers(other, candidate))) {
                owners.add(new Owner(candidate, new ArrayList<>()));
            }
        }
        for (Item candidate : unresolved) {
            if (owners.stream().noneMatch(owner -> owner.item() == candidate)) {
                owners.stream()
                    .filter(owner -> candidate.location().startsWith(owner.item().location()))
                    .findFirst()
                    .ifPresent(owner -> owner.covered().add(candidate));
            }
        }
        return owners;
    }

    private static boolean covers(Item other, Item candidate) {
        Path otherLocation = other.location();
        Path candidateLocation = candidate.location();
        if (candidateLocation.equals(otherLocation)) {
            return TIE_BREAK.compare(other, candidate) < 0;
        }
        return candidateLocation.startsWith(otherLocation);
    }
}
