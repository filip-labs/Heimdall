package dev.heimdall.vehicle;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class SoftwareReleaseService {

    private final SoftwareReleaseRepository repository;

    public SoftwareReleaseService(SoftwareReleaseRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public SoftwareReleaseResponse create(CreateSoftwareReleaseRequest request) {
        if (repository.existsByVersion(request.version())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Software release already exists"
            );
        }

        SoftwareRelease release = new SoftwareRelease(
                request.version(),
                request.description(),
                request.artifactUrl(),
                request.checksum()
        );

        return SoftwareReleaseResponse.from(repository.save(release));
    }

    @Transactional(readOnly = true)
    public SoftwareReleaseResponse getById(UUID id) {
        return repository.findById(id)
                .map(SoftwareReleaseResponse::from)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Software release not found"
                ));
    }

    @Transactional(readOnly = true)
    public List<SoftwareReleaseResponse> getAll() {
        return repository.findAll()
                .stream()
                .map(SoftwareReleaseResponse::from)
                .toList();
    }
}
