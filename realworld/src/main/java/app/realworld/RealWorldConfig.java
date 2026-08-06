// Wires the generated code to Spring. DataSource, DSLContext, TransactionManager and schema.sql are
// all left to Boot's autoconfig; what is added here is the injected implementations, the behaviors
// bound to them, and the two boundary tools the domain knows nothing about — bcrypt and JWT.
package app.realworld;

import app.realworld.web.ArticleViews;
import app.realworld.web.Following;
import app.realworld.web.JwtTokens;

import example.articles.CreateArticle;
import example.articles.DeleteArticle;
import example.articles.ReadArticle;
import example.articles.ReadArticles;
import example.articles.ReadFavoriteCounts;
import example.articles.ReadFavorited;
import example.articles.RemoveArticle;
import example.articles.SlugExists;
import example.articles.StoreArticle;
import example.articles.UpdateArticle;
import example.articles.ReadTags;
import example.articles.StoreFavorite;
import example.articles.StoreUnfavorite;
import example.comments.DeleteComment;
import example.comments.FindComment;
import example.comments.ReadComments;
import example.comments.RemoveComment;
import example.comments.StoreComment;
import example.identity.FindLogin;
import example.identity.FindUserByEmail;
import example.identity.FindUserByName;
import example.identity.Follow;
import example.identity.HashPassword;
import example.identity.LoginUser;
import example.identity.ReadFollowees;
import example.identity.RegisterUser;
import example.identity.StoreFollow;
import example.identity.StorePassword;
import example.identity.StoreUnfollow;
import example.identity.StoreUser;
import example.identity.StoreUserUpdate;
import example.identity.UpdateUser;
import example.identity.VerifyPassword;

import org.jooq.DSLContext;
import org.jooq.conf.RenderQuotedNames;
import org.jooq.conf.Settings;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
public class RealWorldConfig {

    /**
     * Turns off jOOQ identifier quoting, as ordering does. Unquoted names are folded to upper case by
     * H2, so the lower-case names in the code match the schema.
     */
    @Bean
    public Settings jooqSettings() {
        return new Settings().withRenderQuotedNames(RenderQuotedNames.NEVER);
    }

    // --- the two tools the domain does not see ---

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public JwtTokens jwtTokens(@Value("${realworld.jwt.secret}") String secret) {
        return new JwtTokens(secret);
    }

    /** The viewer's followee set, read once per request wherever a `following` flag is answered. */
    @Bean
    public Following following(ReadFollowees readFollowees) {
        return new Following(readFollowees);
    }

    // --- the injected outside-world implementations ---

    @Bean
    public HashPassword hashPassword(PasswordEncoder encoder) {
        return new BcryptPasswords.Hash(encoder);
    }

    @Bean
    public VerifyPassword verifyPassword(PasswordEncoder encoder) {
        return new BcryptPasswords.Verify(encoder);
    }

    @Bean
    public FindUserByEmail findUserByEmail(DSLContext dsl) {
        return new JooqUsers.FindByEmail(dsl);
    }

    @Bean
    public FindUserByName findUserByName(DSLContext dsl) {
        return new JooqUsers.FindByName(dsl);
    }

    @Bean
    public FindLogin findLogin(DSLContext dsl) {
        return new JooqUsers.FindLoginRow(dsl);
    }

    @Bean
    public StoreUser storeUser(DSLContext dsl) {
        return new JooqUsers.Store(dsl);
    }

    @Bean
    public StoreUserUpdate storeUserUpdate(DSLContext dsl) {
        return new JooqUsers.Update(dsl);
    }

    @Bean
    public StorePassword storePassword(DSLContext dsl) {
        return new JooqUsers.SetPassword(dsl);
    }

    @Bean
    public ReadFollowees readFollowees(DSLContext dsl) {
        return new JooqUsers.ReadFollowing(dsl);
    }

    @Bean
    public StoreFollow storeFollow(DSLContext dsl) {
        return new JooqUsers.Follow(dsl);
    }

    @Bean
    public StoreUnfollow storeUnfollow(DSLContext dsl) {
        return new JooqUsers.Unfollow(dsl);
    }

    // --- the composed behaviors, bound to the implementations above ---

    @Bean
    public RegisterUser registerUser(FindUserByEmail findUserByEmail,
                                     FindUserByName findUserByName,
                                     HashPassword hashPassword,
                                     StoreUser storeUser) {
        return RegisterUser.bind(findUserByEmail, findUserByName, hashPassword, storeUser);
    }

    @Bean
    public LoginUser loginUser(FindLogin findLogin, VerifyPassword verifyPassword) {
        return LoginUser.bind(findLogin, verifyPassword);
    }

    @Bean
    public UpdateUser updateUser(FindUserByEmail findUserByEmail,
                                 FindUserByName findUserByName,
                                 StoreUserUpdate storeUserUpdate) {
        return UpdateUser.bind(findUserByEmail, findUserByName, storeUserUpdate);
    }

    @Bean
    public Follow follow(StoreFollow storeFollow) {
        return Follow.bind(storeFollow);
    }

    // --- articles ---

    @Bean
    public SlugExists slugExists(DSLContext dsl) {
        return new JooqArticles.SlugIsTaken(dsl);
    }

    @Bean
    public StoreArticle storeArticle(DSLContext dsl) {
        return new JooqArticles.Store(dsl);
    }

    @Bean
    public ReadArticle readArticle(DSLContext dsl) {
        return new JooqArticles.Read(dsl);
    }

    @Bean
    public RemoveArticle removeArticle(DSLContext dsl) {
        return new JooqArticles.Remove(dsl);
    }

    @Bean
    public ReadArticles readArticles(DSLContext dsl) {
        return new JooqArticles.ReadPage(dsl);
    }

    @Bean
    public ReadFavorited readFavorited(DSLContext dsl) {
        return new JooqArticles.ReadFavoritedSlugs(dsl);
    }

    @Bean
    public ReadFavoriteCounts readFavoriteCounts(DSLContext dsl) {
        return new JooqArticles.ReadCounts(dsl);
    }

    @Bean
    public CreateArticle createArticle(SlugExists slugExists, StoreArticle storeArticle) {
        return CreateArticle.bind(slugExists, storeArticle);
    }

    @Bean
    public UpdateArticle updateArticle(StoreArticle storeArticle) {
        return UpdateArticle.bind(storeArticle);
    }

    @Bean
    public DeleteArticle deleteArticle(RemoveArticle removeArticle) {
        return DeleteArticle.bind(removeArticle);
    }

    @Bean
    public StoreFavorite storeFavorite(DSLContext dsl) {
        return new JooqArticles.Favorite(dsl);
    }

    @Bean
    public StoreUnfavorite storeUnfavorite(DSLContext dsl) {
        return new JooqArticles.Unfavorite(dsl);
    }

    @Bean
    public ReadTags readTags(DSLContext dsl) {
        return new JooqArticles.ReadAllTags(dsl);
    }

    // --- comments ---

    @Bean
    public StoreComment storeComment(DSLContext dsl) {
        return new JooqComments.Store(dsl);
    }

    @Bean
    public ReadComments readComments(DSLContext dsl) {
        return new JooqComments.ReadThread(dsl);
    }

    @Bean
    public FindComment findComment(DSLContext dsl) {
        return new JooqComments.Find(dsl);
    }

    @Bean
    public RemoveComment removeComment(DSLContext dsl) {
        return new JooqComments.Remove(dsl);
    }

    @Bean
    public DeleteComment deleteComment(RemoveComment removeComment) {
        return DeleteComment.bind(removeComment);
    }

    /** The three request-shaped facts every article response carries, read once per response. */
    @Bean
    public ArticleViews articleViews(ReadFavorited readFavorited,
                                     ReadFavoriteCounts readFavoriteCounts,
                                     Following following) {
        return new ArticleViews(readFavorited, readFavoriteCounts, following);
    }
}
