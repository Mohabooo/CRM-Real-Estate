package com.rescrm.inventory.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The project, and what it deliberately cannot do (doc 16 section 7, doc 25 section 3).
 */
@DisplayName("Project")
class ProjectTest {

    private static final UUID TENANT = UUID.randomUUID();

    private static Project ownProject() {
        return Project.create(TENANT, "own_inventory", null, null, "Marassi",
                "North Coast", LocalDate.of(2027, 6, 30));
    }

    @Test
    @DisplayName("starts in draft and holds the model as an opaque code")
    void starts_in_draft() {
        Project project = ownProject();

        assertThat(project.status()).isEqualTo(ProjectStatus.DRAFT);
        assertThat(project.commercialModel()).isEqualTo("own_inventory");
        assertThat(project.developerId()).isNull();
    }

    @Test
    @DisplayName("needs a name in at least one language, not both")
    void one_language_is_enough() {
        assertThat(Project.create(TENANT, "own_inventory", null, "مراسي", null, null, null)
                .displayName()).isEqualTo("مراسي");
        assertThat(ownProject().displayName()).isEqualTo("Marassi");

        assertThatThrownBy(() -> Project.create(TENANT, "own_inventory", null, " ", "  ", null,
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("has no way at all to change its commercial model")
    void the_model_cannot_be_changed() {
        // Doc 25 section 3 puts a model change behind an audited migration that is out of MVP
        // scope. The safest expression of "out of scope" is that no method exists — so this
        // test asserts an absence, which is the only way to assert one.
        boolean anySetter = java.util.Arrays.stream(Project.class.getMethods())
                .anyMatch(method -> method.getName().toLowerCase(java.util.Locale.ROOT)
                        .contains("commercialmodel")
                        && method.getParameterCount() > 0);

        assertThat(anySetter)
                .as("changing the model would retroactively alter financial records")
                .isFalse();
    }

    @Test
    @DisplayName("a brokered project carries its developer")
    void brokered_carries_a_developer() {
        UUID developer = UUID.randomUUID();
        Project project = Project.create(TENANT, "brokered_inventory", developer, null,
                "Badya", null, null);

        assertThat(project.commercialModel()).isEqualTo("brokered_inventory");
        assertThat(project.developerId()).isEqualTo(developer);
    }

    @Test
    @DisplayName("moves draft -> active -> sold_out and reopens")
    void the_documented_lifecycle() {
        Project project = ownProject();

        project.changeStatus(ProjectStatus.ACTIVE);
        assertThat(project.status()).isEqualTo(ProjectStatus.ACTIVE);

        project.changeStatus(ProjectStatus.SOLD_OUT);
        assertThat(project.status()).isEqualTo(ProjectStatus.SOLD_OUT);

        // A cancellation returning a unit to inventory, or a new phase releasing.
        project.changeStatus(ProjectStatus.ACTIVE);
        assertThat(project.status()).isEqualTo(ProjectStatus.ACTIVE);
    }

    @Test
    @DisplayName("cannot skip from draft to sold out")
    void stages_cannot_be_skipped() {
        assertThatThrownBy(() -> ownProject().changeStatus(ProjectStatus.SOLD_OUT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("draft");
    }

    @Test
    @DisplayName("setting the status it already has is a no-op, not an error")
    void idempotent_status_change() {
        Project project = ownProject();
        project.changeStatus(ProjectStatus.DRAFT);
        assertThat(project.status()).isEqualTo(ProjectStatus.DRAFT);
    }

    @Test
    @DisplayName("only an active project is selling")
    void only_active_sells() {
        assertThat(ProjectStatus.ACTIVE.isSelling()).isTrue();
        assertThat(ProjectStatus.DRAFT.isSelling()).isFalse();
        assertThat(ProjectStatus.SOLD_OUT.isSelling()).isFalse();
        assertThat(ProjectStatus.INACTIVE.isSelling()).isFalse();
    }

    @Test
    @DisplayName("a rejected update leaves the names as they were")
    void rejected_update_changes_nothing() {
        Project project = ownProject();

        assertThatThrownBy(() -> project.updateDetails(null, "  ", null, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(project.nameEn()).isEqualTo("Marassi");
    }
}
