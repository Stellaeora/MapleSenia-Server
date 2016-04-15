package handling.channel.handler;

import client.MapleCharacter;
import client.MapleClient;

import client.MapleQuestStatus;
import client.SkillFactory;
import constants.GameConstants;
import constants.MapConstants;
import constants.ServerConstants;
import constants.TutorialConstants;
import handling.cashshop.CashShopServer;
import handling.cashshop.handler.CashShopOperation;
import handling.channel.ChannelServer;
import handling.login.LoginServer;
import handling.world.CharacterIdChannelPair;
import handling.world.CharacterTransfer;
import handling.world.MapleMessenger;
import handling.world.MapleMessengerCharacter;
import handling.world.MapleParty;
import handling.world.MaplePartyCharacter;
import handling.world.PartyOperation;
import handling.world.PlayerBuffStorage;
import handling.world.World;
import handling.world.exped.MapleExpedition;
import handling.world.guild.MapleGuild;
import java.util.List;
import server.Timer.BuffTimer;
import server.maps.FieldLimitType;
import server.maps.MapleMap;
import server.maps.SavedLocationType;
import server.quest.MapleQuest;
import tools.FileoutputUtil;
import tools.HexTool;
import tools.packet.CField;
import tools.Pair;
import tools.Triple;
import tools.data.LittleEndianAccessor;
import tools.packet.CField.UIPacket;
import tools.packet.CWvsContext;
import tools.packet.CWvsContext.BuddylistPacket;
import tools.packet.CWvsContext.ExpeditionPacket;
import tools.packet.CWvsContext.FamilyPacket;
import tools.packet.CWvsContext.GuildPacket;
import tools.packet.MTSCSPacket;

public class InterServerHandler {

    public static final void EnterCS(final MapleClient c, final MapleCharacter chr, final boolean mts) {
        if (chr.hasBlockedInventory() || chr.getMap() == null || chr.getEventInstance() != null || c.getChannelServer() == null || MapConstants.isStorylineMap(chr.getMapId())) {
            c.getSession().write(CField.serverBlocked(2));
            c.getSession().write(CWvsContext.enableActions());
            return;
        }
        if (ServerConstants.BLOCK_CS == true) {
            chr.dropMessage(1, "CashShop has been blocked for the beta server");
            c.getSession().write(CWvsContext.enableActions());
            return;
        }
        if (mts && chr.getLevel() < 50) {
            chr.dropMessage(1, "You may not enter the Maple Trading System until level 50.");
            c.getSession().write(CWvsContext.enableActions());
            return;
        }
        if (World.getPendingCharacterSize() >= 10) {
            chr.dropMessage(1, "The server is busy at the moment. Please try again in a minute or less.");
            c.getSession().write(CWvsContext.enableActions());
            return;
        }
        //if (c.getChannel() == 1 && !c.getPlayer().isGM()) {
        //    c.getPlayer().dropMessage(5, "You may not enter on this channel. Please change channels and try again.");
        //    c.getSession().write(CWvsContext.enableActions());
        //    return;
        //}
        final ChannelServer ch = ChannelServer.getInstance(c.getChannel());

        chr.changeRemoval();

        if (chr.getMessenger() != null) {
            MapleMessengerCharacter messengerplayer = new MapleMessengerCharacter(chr);
            World.Messenger.leaveMessenger(chr.getMessenger().getId(), messengerplayer);
        }
        PlayerBuffStorage.addBuffsToStorage(chr.getId(), chr.getAllBuffs());
        PlayerBuffStorage.addCooldownsToStorage(chr.getId(), chr.getCooldowns());
        PlayerBuffStorage.addDiseaseToStorage(chr.getId(), chr.getAllDiseases());
        World.ChannelChange_Data(new CharacterTransfer(chr), chr.getId(), mts ? -20 : -10);
        ch.removePlayer(chr);
        c.updateLoginState(MapleClient.CHANGE_CHANNEL, c.getSessionIPAddress());
        chr.saveToDB(false, false);
        chr.getMap().removePlayer(chr);
        c.getSession().write(CField.getChannelChange(c, Integer.parseInt(CashShopServer.getIP().split(":")[1])));
        c.setPlayer(null);
        c.setReceiving(false);
    }

    public static final void EnterMTS(final MapleClient c, final MapleCharacter chr) {
        if (chr.hasBlockedInventory() || chr.getMap() == null || chr.getEventInstance() != null || c.getChannelServer() == null) {
            chr.dropMessage(1, "Please try again later.");
            c.getSession().write(CWvsContext.enableActions());
            return;
        }
        /*if (chr.getLevel() < 15) {
            chr.dropMessage(1, "You may not enter the Free Market until level 15.");
            c.getSession().write(CWvsContext.enableActions());
            return;
        }
        if (chr.getMapId() >= 910000000 && chr.getMapId() <= 910000022) {
            chr.dropMessage(1, "You are already in the Free Market.");
            c.getSession().write(CWvsContext.enableActions());
            return;
        }
        chr.dropMessage(5, "You will be transported to the Free Market Entrance.");
        chr.saveLocation(SavedLocationType.fromString("FREE_MARKET"));
        final MapleMap warpz = ChannelServer.getInstance(c.getChannel()).getMapFactory().getMap(910000000);
        if (warpz != null) {
            chr.changeMap(warpz, warpz.getPortal("st00"));
        } else {
            chr.dropMessage(5, "Please try again later.");
        }*/
        c.getSession().write(CWvsContext.enableActions());
    }

    public static final void Loggedin(final int playerid, final MapleClient c) {
        final MapleCharacter player;

        CharacterTransfer transfer = CashShopServer.getPlayerStorage().getPendingCharacter(playerid);
        if (transfer != null) {
            CashShopOperation.EnterCS(transfer, c);
            return;
        }
        for (ChannelServer cserv : ChannelServer.getAllInstances()) {
            transfer = cserv.getPlayerStorage().getPendingCharacter(playerid);
            if (transfer != null) {
                c.setChannel(cserv.getChannel());
                break;
            }
        }

        if (transfer == null) { // Player isn't in storage, probably isn't CC
            Triple<String, String, Integer> ip = LoginServer.getLoginAuth(playerid);
            String s = c.getSessionIPAddress();
            if (ip == null || !s.substring(s.indexOf('/') + 1, s.length()).equals(ip.left)) {
                if (ip != null) {
                    LoginServer.putLoginAuth(playerid, ip.left, ip.mid, ip.right);
                }
                c.getSession().close();
                return;
            }
            c.setTempIP(ip.mid);
            c.setChannel(ip.right);
            player = MapleCharacter.loadCharFromDB(playerid, c, true);
        } else {
            player = MapleCharacter.ReconstructChr(transfer, c, true);
        }
        final ChannelServer channelServer = c.getChannelServer();

        c.setPlayer(player);
        c.setAccID(player.getAccountID());

        if (!c.CheckIPAddress()) { // Remote hack
            c.getSession().close();
            return;
        }

        final int state = c.getLoginState();
        boolean allowLogin = false;

        if (state == MapleClient.LOGIN_SERVER_TRANSITION || state == MapleClient.CHANGE_CHANNEL || state == MapleClient.LOGIN_NOTLOGGEDIN) {
            allowLogin = !World.isCharacterListConnected(c.loadCharacterNames(c.getWorld()));
        }
        if (!allowLogin) {
            c.setPlayer(null);
            c.getSession().close();
            return;
        }
        c.updateLoginState(MapleClient.LOGIN_LOGGEDIN, c.getSessionIPAddress());
        channelServer.addPlayer(player);

        player.giveCoolDowns(PlayerBuffStorage.getCooldownsFromStorage(player.getId()));
        player.silentGiveBuffs(PlayerBuffStorage.getBuffsFromStorage(player.getId()));
        player.giveSilentDebuff(PlayerBuffStorage.getDiseaseFromStorage(player.getId()));

        c.getSession().write(CField.getCharInfo(player));
        c.getSession().write(MTSCSPacket.enableCSUse());

        /*
         * if (player.isGM()) { SkillFactory.getSkill(GameConstants.GMS ?
         * 9101004 : 9001004).getEffect(1).applyTo(player);
        }
         */
      /*  if (MapConstants.isStorylineMap(c.getPlayer().getMapId())) {
            if (!c.getPlayer().hasSummon()) {
                c.getPlayer().setHasSummon(true);
                c.getSession().write(UIPacket.summonHelper(true));
                c.getSession().write(UIPacket.summonMessage(TutorialConstants.getTutorialTalk(c.getPlayer(), c.getPlayer().getMapId())));
            }
        }*/
        c.getSession().write(CWvsContext.temporaryStats_Reset()); // .
        player.getMap().addPlayer(player);
        try {
            // Start of buddylist
            final int buddyIds[] = player.getBuddylist().getBuddyIds();
            World.Buddy.loggedOn(player.getName(), player.getId(), c.getChannel(), buddyIds);
            if (player.getParty() != null) {
                final MapleParty party = player.getParty();
                World.Party.updateParty(party.getId(), PartyOperation.LOG_ONOFF, new MaplePartyCharacter(player));

                if (party != null && party.getExpeditionId() > 0) {
                    final MapleExpedition me = World.Party.getExped(party.getExpeditionId());
                    if (me != null) {
                         c.getSession().write(CWvsContext.ExpeditionPacket.expeditionStatus(me, false, true));
                    }
                }
            }
            final CharacterIdChannelPair[] onlineBuddies = World.Find.multiBuddyFind(player.getId(), buddyIds);
            for (CharacterIdChannelPair onlineBuddy : onlineBuddies) {
                player.getBuddylist().get(onlineBuddy.getCharacterId()).setChannel(onlineBuddy.getChannel());
            }
            c.getSession().write(BuddylistPacket.updateBuddylist(player.getBuddylist().getBuddies()));

            // Start of Messenger
            final MapleMessenger messenger = player.getMessenger();
            if (messenger != null) {
                World.Messenger.silentJoinMessenger(messenger.getId(), new MapleMessengerCharacter(c.getPlayer()));
                World.Messenger.updateMessenger(messenger.getId(), c.getPlayer().getName(), c.getChannel());
            }

            // Start of Guild and alliance
            if (player.getGuildId() > 0) {
                World.Guild.setGuildMemberOnline(player.getMGC(), true, c.getChannel());
                c.getSession().write(GuildPacket.showGuildInfo(player));
                final MapleGuild gs = World.Guild.getGuild(player.getGuildId());
                if (gs != null) {
                    final List<byte[]> packetList = World.Alliance.getAllianceInfo(gs.getAllianceId(), true);
                    if (packetList != null) {
                        for (byte[] pack : packetList) {
                            if (pack != null) {
                                c.getSession().write(pack);
                            }
                        }
                    }
                } else { //guild not found, change guild id
                    player.setGuildId(0);
                    player.setGuildRank((byte) 5);
                    player.setAllianceRank((byte) 5);
                    player.saveGuildStatus();
                }
            }

            if (player.getFamilyId() > 0) {
                World.Family.setFamilyMemberOnline(player.getMFC(), true, c.getChannel());
            }
            c.getSession().write(FamilyPacket.getFamilyData());
            c.getSession().write(FamilyPacket.getFamilyInfo(player));
        } catch (Exception e) {
            FileoutputUtil.outputFileError(FileoutputUtil.Login_Error, e);
        }
        player.getClient().getSession().write(CWvsContext.serverMessage(channelServer.getServerMessage()));
        player.sendMacros();
        player.showNote();
        player.sendImp();
        player.updatePartyMemberHP();
        player.startFairySchedule(false);
        player.baseSkills(); //fix people who've lost skills.
        c.getSession().write(CField.getKeymap(player.getKeyLayout()));
        player.updatePetAuto();
        player.expirationTask(true, transfer == null);
        if (player.getJob() == 132) { // DARKKNIGHT
            player.checkBerserk();
        }
        player.spawnClones();
        player.spawnSavedPets();
        if (player.getStat().equippedSummon > 0) {
            SkillFactory.getSkill(player.getStat().equippedSummon).getEffect(1).applyTo(player);
        }
        MapleQuestStatus stat = player.getQuestNoAdd(MapleQuest.getInstance(GameConstants.PENDANT_SLOT));
        c.getSession().write(CWvsContext.pendantSlot(stat != null && stat.getCustomData() != null && Long.parseLong(stat.getCustomData()) > System.currentTimeMillis()));
       stat = player.getQuestNoAdd(MapleQuest.getInstance(GameConstants.QUICK_SLOT));
       c.getSession().write(CField.quickSlot(stat != null && stat.getCustomData() != null ? stat.getCustomData() : null));
       c.getSession().write(CWvsContext.getFamiliarInfo(player));
               
       player.applyInners(); 
       if(player.getPendingBuffPackets().size() > 0){
           BuffTimer.getInstance().schedule(new Runnable(){
                @Override
                public void run(){
                    for(byte[] packet : player.getPendingBuffPackets()){
                        c.getSession().write(packet);
                    }
                    player.getPendingBuffPackets().clear();
                }
           }, 1000);
           
       }
    }

    public static final void ChangeChannel(final LittleEndianAccessor slea, final MapleClient c, final MapleCharacter chr, final boolean room) {
        if (chr == null || chr.hasBlockedInventory() || chr.getEventInstance() != null || chr.getMap() == null || chr.isInBlockedMap() || FieldLimitType.ChannelSwitch.check(chr.getMap().getFieldLimit())) {
            c.getSession().write(CWvsContext.enableActions());
            return;
        }
        if (World.getPendingCharacterSize() >= 10) {
            chr.dropMessage(1, "The server is busy at the moment. Please try again in a less than a minute.");
            c.getSession().write(CWvsContext.enableActions());
            return;
        }
        final int chc = slea.readByte() + 1;
        int mapid = 0;
        if (room) {
            mapid = slea.readInt();
        }
        chr.updateTick(slea.readInt());
        if (!World.isChannelAvailable(chc)) {
            chr.dropMessage(1, "The channel is full at the moment.");
            c.getSession().write(CWvsContext.enableActions());
            return;
        }
        if (room && (mapid < 910000001 || mapid > 910000022)) {
            chr.dropMessage(1, "The channel is full at the moment.");
            c.getSession().write(CWvsContext.enableActions());
            return;
        }
        if (room) {
            if (chr.getMapId() == mapid) {
                if (c.getChannel() == chc) {
                    chr.dropMessage(1, "You are already in " + chr.getMap().getMapName());
                    c.getSession().write(CWvsContext.enableActions());
                } else { // diff channel
                    chr.changeChannel(chc);
                }
            } else { // diff map
                if (c.getChannel() != chc) {
                    chr.changeChannel(chc);
                }
                final MapleMap warpz = ChannelServer.getInstance(c.getChannel()).getMapFactory().getMap(mapid);
                if (warpz != null) {
                    chr.changeMap(warpz, warpz.getPortal("out00"));
                } else {
                    chr.dropMessage(1, "The channel is full at the moment.");
                    c.getSession().write(CWvsContext.enableActions());
                }
            }
        } else {
            chr.changeChannel(chc);
        }
    }
}
