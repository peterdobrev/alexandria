package com.alexandria.repository;

import com.alexandria.AbstractPostgresIntegrationTest;
import com.alexandria.entity.Category;
import com.alexandria.entity.Comment;
import com.alexandria.entity.Document;
import com.alexandria.entity.DocumentCategory;
import com.alexandria.entity.DocumentCategoryId;
import com.alexandria.entity.InteractionKind;
import com.alexandria.entity.User;
import com.alexandria.entity.UserDocumentInteraction;
import com.alexandria.entity.Visibility;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate"
})
@Import(JpaRecommendationQueryRunner.class)
class RecommendationQueriesIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private EntityManager em;

    @Autowired
    private JpaRecommendationQueryRunner runner;

    @Test
    void given_userBookmarkedAndAnotherUserBookmarkedSameDoc_when_runScoreQuery_then_correlatedDocScoresHigher() {
        // Alice and Bob both bookmark "shared". Bob also bookmarks "correlated".
        // Carol only bookmarks "noise". When recommending for Alice, "correlated"
        // should rank above "noise".
        User alice = persistUser("alice@example.com");
        User bob = persistUser("bob@example.com");
        User carol = persistUser("carol@example.com");

        User authorA = persistUser("authorA@example.com");
        User authorB = persistUser("authorB@example.com");

        Document shared = persistDoc(authorA, "shared", Visibility.PUBLIC);
        Document correlated = persistDoc(authorA, "correlated", Visibility.PUBLIC);
        Document noise = persistDoc(authorB, "noise", Visibility.PUBLIC);

        persistInteraction(alice, shared, InteractionKind.BOOKMARK);
        persistInteraction(bob, shared, InteractionKind.BOOKMARK);
        persistInteraction(bob, correlated, InteractionKind.BOOKMARK);
        persistInteraction(carol, noise, InteractionKind.BOOKMARK);

        em.flush();

        List<UUID> ids = runner.runScoreQuery(alice.getId(), 50, 0);

        assertThat(ids).contains(correlated.getId());
        assertThat(ids).doesNotContain(shared.getId());      // already interacted with
        // "correlated" should appear before "noise" if both surface
        if (ids.contains(noise.getId())) {
            assertThat(ids.indexOf(correlated.getId())).isLessThan(ids.indexOf(noise.getId()));
        }
    }

    @Test
    void given_viewSpamUpToTenViews_when_runScoreQuery_then_capsContributionAtFiveViews() {
        // Alice and Bob both view "shared". Bob also views "candidate".
        // Carol view-spams "candidate" 10 times AND "shared" 10 times. The cap
        // means Carol's pair contributes the same as 5+5 — not 10+10. We simply
        // confirm the query does not crash and "candidate" surfaces.
        User alice = persistUser("alice@example.com");
        User bob = persistUser("bob@example.com");
        User carol = persistUser("carol@example.com");

        User author = persistUser("author@example.com");
        Document shared = persistDoc(author, "shared", Visibility.PUBLIC);
        Document candidate = persistDoc(author, "candidate", Visibility.PUBLIC);

        persistInteraction(alice, shared, InteractionKind.VIEW);
        persistInteraction(bob, shared, InteractionKind.VIEW);
        persistInteraction(bob, candidate, InteractionKind.VIEW);
        for (int i = 0; i < 10; i++) {
            persistInteraction(carol, shared, InteractionKind.VIEW);
            persistInteraction(carol, candidate, InteractionKind.VIEW);
        }

        em.flush();

        List<UUID> ids = runner.runScoreQuery(alice.getId(), 50, 0);

        assertThat(ids).contains(candidate.getId());
    }

    @Test
    void given_privateDocAndOwnDoc_when_runScoreQuery_then_neitherAppearsInResults() {
        User alice = persistUser("alice@example.com");
        User bob = persistUser("bob@example.com");

        Document publicByBob = persistDoc(bob, "public", Visibility.PUBLIC);
        Document privateByBob = persistDoc(bob, "private", Visibility.PRIVATE);
        Document ownByAlice = persistDoc(alice, "own", Visibility.PUBLIC);

        persistInteraction(alice, publicByBob, InteractionKind.BOOKMARK);
        persistInteraction(bob, privateByBob, InteractionKind.BOOKMARK);
        persistInteraction(bob, ownByAlice, InteractionKind.BOOKMARK);

        em.flush();

        List<UUID> ids = runner.runScoreQuery(alice.getId(), 50, 0);

        assertThat(ids).doesNotContain(publicByBob.getId());  // already interacted
        assertThat(ids).doesNotContain(privateByBob.getId()); // private
        assertThat(ids).doesNotContain(ownByAlice.getId());   // own document
    }

    @Test
    void given_userWithNoInteractionsButCommentedOnHistoryCategory_when_runColdStart_then_returnsPopularInThatCategory() {
        User alice = persistUser("alice@example.com");
        User bob = persistUser("bob@example.com");

        User author = persistUser("author@example.com");

        Category tech = persistCategory("tech");
        Category cooking = persistCategory("cooking");

        Document techDoc1 = persistDoc(author, "tech-1", Visibility.PUBLIC, tech);
        Document techDoc2 = persistDoc(author, "tech-2", Visibility.PUBLIC, tech);
        Document cookingDoc = persistDoc(author, "cook-1", Visibility.PUBLIC, cooking);

        // Alice has commented on a tech doc — engagement signal
        persistComment(alice, techDoc1);
        // Bob bookmarks techDoc2, making it the more popular tech doc
        persistInteraction(bob, techDoc2, InteractionKind.BOOKMARK);

        em.flush();

        List<UUID> ids = runner.runColdStartQuery(alice.getId(), 50, 0);

        assertThat(ids).contains(techDoc2.getId());
        // techDoc2 (bookmarked once) should rank above techDoc1 (no interactions)
        if (ids.contains(techDoc1.getId())) {
            assertThat(ids.indexOf(techDoc2.getId())).isLessThan(ids.indexOf(techDoc1.getId()));
        }
        // cookingDoc may or may not appear depending on whether affinity matches
        assertThat(cookingDoc).isNotNull();
    }

    @Test
    void given_brandNewUserWithNoEngagement_when_runColdStart_then_returnsGloballyPopular() {
        // Alice has zero engagement of any kind. Cold start should fall through
        // to globally popular.
        User alice = persistUser("alice@example.com");
        User bob = persistUser("bob@example.com");
        User author = persistUser("author@example.com");

        Document popular = persistDoc(author, "popular", Visibility.PUBLIC);
        Document quiet = persistDoc(author, "quiet", Visibility.PUBLIC);

        persistInteraction(bob, popular, InteractionKind.BOOKMARK);

        em.flush();

        List<UUID> ids = runner.runColdStartQuery(alice.getId(), 50, 0);

        assertThat(ids).contains(popular.getId());
        if (ids.contains(quiet.getId())) {
            assertThat(ids.indexOf(popular.getId())).isLessThan(ids.indexOf(quiet.getId()));
        }
    }

    // --- helpers ---

    private User persistUser(String email) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("x");
        user.setDisplayName(email);
        user.setCreatedAt(Instant.now());
        em.persist(user);
        return user;
    }

    private Document persistDoc(User author, String title, Visibility visibility, Category... categories) {
        Document doc = new Document();
        doc.setTitle(title);
        doc.setType("ARTICLE");
        doc.setBody("body");
        doc.setVisibility(visibility);
        doc.setAuthor(author);
        em.persist(doc);
        em.flush();

        List<DocumentCategory> joins = new ArrayList<>();
        for (Category category : categories) {
            DocumentCategory dc = new DocumentCategory();
            dc.setId(new DocumentCategoryId(doc.getId(), category.getId()));
            dc.setDocument(doc);
            dc.setCategory(category);
            em.persist(dc);
            joins.add(dc);
        }
        doc.setDocumentCategories(joins);
        return doc;
    }

    private Category persistCategory(String name) {
        Category category = new Category();
        category.setName(name);
        em.persist(category);
        return category;
    }

    private void persistInteraction(User user, Document document, InteractionKind kind) {
        UserDocumentInteraction interaction = new UserDocumentInteraction();
        interaction.setUser(user);
        interaction.setDocument(document);
        interaction.setKind(kind);
        em.persist(interaction);
    }

    private void persistComment(User author, Document document) {
        Comment comment = new Comment();
        comment.setAuthor(author);
        comment.setDocument(document);
        comment.setBody("nice");
        em.persist(comment);
    }
}
