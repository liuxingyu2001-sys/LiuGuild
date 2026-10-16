package com.liu.liuguild.war;

import com.liu.liuguild.db.*;
import com.liu.liuguild.model.Guild;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class SeasonRepositoryTest {
    @TempDir Path temp;
    Database db; SeasonRepository repo; GuildRepository guilds;
    @BeforeEach void setup() throws Exception {
        DbConfig config=new DbConfig(); config.type="sqlite"; config.sqliteFile=temp.resolve("season.db").toString();
        db=new Database(config,Logger.getLogger("SeasonTest")); db.init();
        repo=new SeasonRepository(db); repo.init(); guilds=new GuildRepository(db);
    }
    @AfterEach void close() { db.shutdown(); }
    private SeasonRepository.Event event(String id) { return new SeasonRepository.Event(id,"season","zy","REGISTERING",1000,100); }
    @Test void hostCannotBeDoubleBookedAndCancellationReleasesIt() throws Exception {
        assertTrue(repo.create(event("one")));
        assertThrows(java.sql.SQLException.class,()->repo.create(event("two")));
        assertNull(repo.event("two"));
        repo.finish("one",List.of(),Map.of(),true);
        assertEquals("CANCELLED",repo.event("one").state());
        assertTrue(repo.create(event("two")));
    }
    @Test void entriesAndReturnCoordinatesSurviveRestart() throws Exception {
        repo.create(event("one")); UUID uuid=UUID.randomUUID();
        var entry=new SeasonRepository.Entry("one",uuid,"g","99","world",1,2,3,45,6,"REGISTERED");
        repo.enter(entry);
        assertEquals(List.of(entry),new SeasonRepository(db).entries("one"));
        assertTrue(repo.pendingReturns().isEmpty());
        repo.create(new SeasonRepository.Event("two","season","fb","REGISTERING",1000,100));
        assertThrows(java.sql.SQLException.class, () -> repo.enter(new SeasonRepository.Entry("two",uuid,"g","99","world",1,2,3,45,6,"REGISTERED")));
        repo.finish("one",List.of(),Map.of(),true);
        assertEquals(List.of(entry),repo.pendingReturns());
        repo.entryState("one",uuid,"RETURNED");
        assertTrue(repo.pendingReturns().isEmpty());
        repo.enter(new SeasonRepository.Entry("two",uuid,"g","99","world",1,2,3,45,6,"REGISTERED"));
    }
    @Test void failedRewardWriteRollsBackWholeSettlement() throws Exception {
        repo.create(event("one"));
        var duplicate=List.of(new SeasonBattle.Standing("g",1,2,0,1),new SeasonBattle.Standing("g",1,2,0,1));
        assertThrows(java.sql.SQLException.class, () -> repo.finish("one",duplicate,Map.of(1,1L),false));
        assertEquals("REGISTERING",repo.event("one").state());
        assertThrows(java.sql.SQLException.class, () -> repo.create(event("blocked")));
        repo.finish("one",List.of(new SeasonBattle.Standing("g",1,2,0,1)),Map.of(),false);
        assertEquals("FINISHED",repo.event("one").state());
    }

    @Test void unifiedDestinationPersistsAcrossServers() throws Exception {
        repo.create(event("one")); UUID player=UUID.randomUUID();
        repo.enter(new SeasonRepository.Entry("one",player,"g","99","old",1,2,3,4,5,"ADMITTED"));
        repo.destination("one","fb","lobby",new SeasonRepository.ArenaDestination(10,70,20,90,0));
        repo.finish("one",List.of(),Map.of(),false);
        var entry=repo.pendingReturns().getFirst();
        assertEquals("fb",entry.source()); assertEquals("lobby",entry.world()); assertEquals(70,entry.y());
    }

    @Test void rankingRewardsAreAtomicAndIdempotent() throws Exception {
        Guild g=new Guild("guild","Guild","TAG",UUID.randomUUID(),"Leader","","",false,1,1,0,1,0,0,0,"test",1,"default");
        guilds.insertGuild(g); repo.create(event("one"));
        var ranks=List.of(new SeasonBattle.Standing("guild",1,10,2,1));
        repo.finish("one",ranks,Map.of(1,5000L),false);
        repo.finish("one",ranks,Map.of(1,5000L),false);
        assertEquals(5000,guilds.loadGuild("guild").points());
        assertEquals("FINISHED",repo.event("one").state());
        repo.state("one","RUNNING",500);
        assertEquals("FINISHED",repo.event("one").state());
    }
}
