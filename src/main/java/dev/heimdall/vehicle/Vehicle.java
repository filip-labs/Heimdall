package dev.heimdall.vehicle;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vehicle")
public class Vehicle {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 17)
    private String vin;

    @Column(name = "software_version", nullable = false)
    private String softwareVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "connectivity_status", nullable = false)
    private ConnectivityStatus connectivityStatus;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Vehicle() {
    }

    public Vehicle(String vin, String softwareVersion) {
        Instant now = Instant.now();

        this.id = UUID.randomUUID();
        this.vin = vin;
        this.softwareVersion = softwareVersion;
        this.connectivityStatus = ConnectivityStatus.OFFLINE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void heartbeat() {
        Instant now = Instant.now();

        this.connectivityStatus = ConnectivityStatus.ONLINE;
        this.lastSeenAt = now;
        this.updatedAt = now;
    }

    public void markOffline() {
        this.connectivityStatus = ConnectivityStatus.OFFLINE;
        this.updatedAt = Instant.now();
    }

    public void installSoftwareVersion(String softwareVersion) {
        this.softwareVersion = softwareVersion;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getVin() {
        return vin;
    }

    public String getSoftwareVersion() {
        return softwareVersion;
    }

    public ConnectivityStatus getConnectivityStatus() {
        return connectivityStatus;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
