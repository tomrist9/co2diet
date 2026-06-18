package common.entity;


import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@MappedSuperclass
public class BaseEntity {
    @Id
    @GeneratedValue(strategy=GenerationType.IDENTITY)
    Long id;

    private Instant createdAt;
    private Instant updatedAt;


    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        Instant now = Instant.now();
        this.updatedAt = now;
    }
}
