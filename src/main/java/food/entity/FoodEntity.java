package food.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "foods")
public class FoodEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String brand;
    private String category;

    @Column(unique = true)
    private String barcode;

    private String source;
    private Boolean verified;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
