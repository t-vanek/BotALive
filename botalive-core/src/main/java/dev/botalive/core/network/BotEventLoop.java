package dev.botalive.core.network;

import io.netty.channel.EventLoopGroup;
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

/**
 * Řízené vypnutí sdíleného netty event-loopu MCProtocolLib při disable pluginu.
 *
 * <p>Skupina knihovny je statická, líně vytvářená v
 * {@code ClientNetworkSession.createEventLoopGroup()} – a nikdo ji nevypíná.
 * Její vlákna po zavření plugin classloaderu líně donačítají relokované třídy
 * ze zavřeného jaru: každý shutdown se živými boty pak sype „zip file error"
 * a může utnout poslední write-behind zápis. Náhrada vlastní skupinou
 * (override {@code getEventLoopGroup()}) se ukázala riziková – v každé ze tří
 * session s ní selhalo připojení jednoho bota na dekódovací chybě (zlib/JSON/
 * buffer limit; 0/3 session bez ní, příčina nedořešena) – proto se skupina
 * knihovny nechává za běhu na pokoji a při vypnutí se jen reflexí zavře.
 * Pole je private static v témže (relokovaném) jaru, reflexe je legální.</p>
 */
public final class BotEventLoop {

    private BotEventLoop() {
    }

    /**
     * Zavře event-loop knihovny – volat při vypnutí pluginu až PO odpojení
     * všech botů. Selhání reflexe jen zaloguje (degradace = původní chování:
     * hlučný shutdown, žádná škoda za běhu).
     */
    public static void shutdown() {
        try {
            Field field = ClientNetworkSession.class.getDeclaredField("EVENT_LOOP_GROUP");
            field.setAccessible(true);
            Object group = field.get(null);
            if (group instanceof EventLoopGroup elg) {
                elg.shutdownGracefully(50, 2000, TimeUnit.MILLISECONDS).await(3000);
                field.set(null, null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ReflectiveOperationException | RuntimeException e) {
            LoggerFactory.getLogger(BotEventLoop.class)
                    .warn("Vypnutí event-loopu knihovny se nepovedlo: {}", e.toString());
        }
    }
}
