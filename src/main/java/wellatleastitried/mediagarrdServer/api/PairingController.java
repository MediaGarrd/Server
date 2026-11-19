package wellatleastitried.mediagarrdServer.api;

import static wellatleastitried.mediagarrdServer.api.ApiExceptionHandler.error;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import wellatleastitried.mediagarrdServer.dto.PairRequest;
import wellatleastitried.mediagarrdServer.dto.PairResponse;
import wellatleastitried.mediagarrdServer.dto.PairedClientDto;
import wellatleastitried.mediagarrdServer.dto.PairingCodeDto;
import wellatleastitried.mediagarrdServer.service.PairingService;
import wellatleastitried.mediagarrdServer.utilities.LogSanitizer;

@RestController
@RequestMapping("/api/v1")
public class PairingController {

    private static final Logger LOGGER = LoggerFactory.getLogger(PairingController.class);

    private final PairingService pairing;

    public PairingController(PairingService pairing) {
        this.pairing = pairing;
    }

    @PublicEndpoint
    @PostMapping("/pair")
    public PairResponse pair(@RequestBody PairRequest request) {
        LOGGER.info("POST /api/v1/pair");
        return pairing.pair(request.pin(), request.name());
    }

    @PostMapping("/pairing-codes")
    public PairingCodeDto createCode() {
        LOGGER.info("POST /api/v1/pairing-codes");
        return pairing.createCode();
    }

    @GetMapping("/clients")
    public List<PairedClientDto> clients(@RequestAttribute(ApiAuthInterceptor.CLIENT_ID_ATTRIBUTE) String currentId) {
        return pairing.list().stream()
            .map(client -> new PairedClientDto(client.id(), client.name(), client.createdAt(), client.lastUsedAt(), client.id().equals(currentId)))
            .toList();
    }

    @DeleteMapping("/clients/{id}")
    public ResponseEntity<?> revoke(@PathVariable String id) {
        LOGGER.info("DELETE /api/v1/clients/{}", LogSanitizer.clean(id));
        if (!pairing.revoke(id)) {
            return error(HttpStatus.NOT_FOUND, "Client not found");
        }
        return ResponseEntity.noContent().build();
    }
}
