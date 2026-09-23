package com.cryptosignals.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * A named, persisted parameter set. History is kept so a stored level can always be traced back to
 * the exact rules that produced it, via {@code fingerprint}.
 */
@Entity
@Table(name = "analysis_configuration")
public class AnalysisConfigurationEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private String name;
    @Column(nullable = false) private String fingerprint;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private String payload;
    @Column(nullable = false) private boolean active;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    protected AnalysisConfigurationEntity() { }

    public AnalysisConfigurationEntity(String name, String fingerprint, String payload, boolean active) {
        this.name = name;
        this.fingerprint = fingerprint;
        this.payload = payload;
        this.active = active;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String v) { this.fingerprint = v; }
    public String getPayload() { return payload; }
    public void setPayload(String v) { this.payload = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }
    public Instant getCreatedAt() { return createdAt; }
}
