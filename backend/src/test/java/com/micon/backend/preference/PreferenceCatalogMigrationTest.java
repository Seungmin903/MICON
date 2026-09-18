package com.micon.backend.preference;

import static org.assertj.core.api.Assertions.assertThat;
import com.micon.backend.domain.preference.api.PreferenceResponse.Genre;
import com.micon.backend.domain.preference.repository.PreferenceCatalogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:preference-catalog;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
class PreferenceCatalogMigrationTest {
    @Autowired PreferenceCatalogRepository catalog;
    @Autowired JdbcTemplate jdbc;

    @Test void freshDatabaseOffersTenUniqueOrderedChoicesWithoutSeedingFakeSingers() {
        var genres = catalog.findGenres();
        assertThat(genres).extracting(Genre::name).containsExactly(
            "발라드", "댄스", "R&B/소울", "록/메탈", "힙합", "포크/어쿠스틱", "OST", "영어곡", "일본곡", "트로트");
        assertThat(genres).extracting(Genre::code).doesNotHaveDuplicates();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM artist_portrait", Integer.class)).isEqualTo(54);
        assertThat(jdbc.queryForObject("SELECT name FROM genre WHERE normalized_name='kpop-adult'", String.class)).isEqualTo("성인가요");
        assertThat(jdbc.queryForObject("SELECT name FROM genre WHERE normalized_name='global-pop'", String.class)).isEqualTo("팝");
        assertThat(jdbc.queryForObject("SELECT name FROM genre WHERE normalized_name='etc-jpop'", String.class)).isEqualTo("J-POP");
    }
}
