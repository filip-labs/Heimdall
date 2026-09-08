package dev.heimdall.release;

import dev.heimdall.api.ConflictException;
import dev.heimdall.api.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SoftwareReleaseService {

    private final SoftwareReleaseRepository repository;

    @Transactional
    public SoftwareReleaseResponse create(CreateSoftwareReleaseRequest request) {
        if (repository.existsByVersion(request.version())) {
            throw new ConflictException("Software release already exists");
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
                .orElseThrow(() -> new ResourceNotFoundException("Software release not found"));
    }

    @Transactional(readOnly = true)
    public List<SoftwareReleaseResponse> getAll() {
        return repository.findAll()
                .stream()
                .map(SoftwareReleaseResponse::from)
                .toList();
    }
}
