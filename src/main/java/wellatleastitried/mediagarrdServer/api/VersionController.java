package wellatleastitried.mediagarrdServer.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import wellatleastitried.mediagarrdServer.dto.VersionStatusDto;
import wellatleastitried.mediagarrdServer.service.UpdateCheckService;

@RestController
@RequestMapping("/api/v1")
public class VersionController {

    private final UpdateCheckService updateCheckService;

    public VersionController(UpdateCheckService updateCheckService) {
        this.updateCheckService = updateCheckService;
    }

    @GetMapping("/version")
    public VersionStatusDto version() {
        return updateCheckService.status();
    }
}
