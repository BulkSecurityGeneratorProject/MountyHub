package com.mountyhub.app.service;

import com.mountyhub.app.domain.Gear;
import com.mountyhub.app.domain.ScriptCall;
import com.mountyhub.app.domain.Troll;
import com.mountyhub.app.domain.User;
import com.mountyhub.app.domain.enumeration.GearType;
import com.mountyhub.app.domain.enumeration.ScriptName;
import com.mountyhub.app.domain.enumeration.ScriptType;
import com.mountyhub.app.domain.enumeration.TrollRace;
import com.mountyhub.app.exception.MountyHallScriptException;
import com.mountyhub.app.exception.MountyHubException;
import com.mountyhub.app.repository.GearRepository;
import com.mountyhub.app.repository.ScriptCallRepository;
import com.mountyhub.app.repository.TrollRepository;
import com.mountyhub.app.repository.UserRepository;
import com.mountyhub.app.service.util.DateUtil;
import com.mountyhub.app.service.util.MountyHallScriptUtil;
import com.mountyhub.app.service.util.MountyHallUtil;
import com.mountyhub.app.web.rest.dto.GearDTO;
import com.mountyhub.app.web.rest.dto.ProfilDTO;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.inject.Inject;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Service class for managing trolls.
 */
@Service
@Transactional
public class TrollService {

    private final Logger log = LoggerFactory.getLogger(TrollService.class);

    @Inject
    private UserRepository userRepository;

    @Inject
    private UserService userService;

    @Inject
    private TrollRepository trollRepository;

    @Inject
    private ScriptCallRepository scriptCallRepository;

    @Inject
    private GearRepository gearRepository;

    @Inject
    private ScriptCallService scriptCallService;

    public Troll createUpdateTroll(Troll troll) throws IOException, MountyHubException, MountyHallScriptException {
        // Check if the troll isn't already linked to a user for a troll creation
        User currentUser = null;
        if (troll.getUser() == null) {
            currentUser = userService.getUserWithAuthorities();

            // A troll is already linked to the current user
            if (currentUser.getTroll() != null) {
                throw new MountyHubException("Votre utilisateur a déjà un troll lié !");
            }

            // Troll already present with a user linked
            Optional<User> userResult = userRepository.findOneByTrollNumber(troll.getNumber());
            if (userResult.isPresent()) {
                throw new MountyHubException("Le numero de troll " + troll.getNumber() + " est déjà associé à l'utilisateur " + userResult.get().getLogin() + " !");
            }
        }

        // Caract, position and profil call
        ScriptCall caractCall = MountyHallScriptUtil.createScriptCall(ScriptName.SP_Caract);
        ScriptCall positionCall = MountyHallScriptUtil.createScriptCall(ScriptName.SP_Profil3);
        ScriptCall profilCall = MountyHallScriptUtil.createScriptCall(ScriptName.SP_ProfilPublic2);
        ScriptCall gearCall = MountyHallScriptUtil.createScriptCall(ScriptName.SP_Equipement);

        try {
            // Set characteristic, position and profil
            scriptCallService.callScript(caractCall, troll);
            setTrollCharacteristicFromMHScript(caractCall, troll);
            scriptCallService.callScript(positionCall, troll);
            setTrollPositionFromMHScript(positionCall, troll);
            scriptCallService.callScript(profilCall, troll);
            setTrollProfilFromMHScript(profilCall, troll);

            // Save the troll
            trollRepository.save(troll);
            log.debug("Created Information for Troll: {}", troll);

            // Save gear
            scriptCallService.callScript(gearCall, troll);
            gearCall.setTroll(troll);
            setTrollGearFromMHScript(gearCall);

            // Save script calls with troll number
            caractCall.setTroll(troll);
            scriptCallRepository.save(caractCall);
            positionCall.setTroll(troll);
            scriptCallRepository.save(positionCall);
            profilCall.setTroll(troll);
            scriptCallRepository.save(profilCall);

            // Link troll to current user
            if (currentUser != null) {
                currentUser.setTroll(troll);
                userRepository.save(currentUser);
            }

            return troll;
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new MountyHallScriptException("Erreur lors du parsage du script MountyHall !", e);
        }
    }

    public void setTrollGearFromMHScript(ScriptCall scriptCall) throws IOException, MountyHallScriptException, NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        String[] lines = StringUtils.split(scriptCall.getBody(), "\n");

        scriptCall.setSuccessful(true);
        scriptCallRepository.save(scriptCall);

        List<Long> weared = new ArrayList<>();

        for (String line : lines) {
            line = StringUtils.replace(line, "\\'", "'");
            String[] values = StringUtils.splitByWholeSeparatorPreserveAllTokens(line, ";");

            // ID, équipé ? ; type ; identifié ? ; nom ; magie ; description ; poids
            Long number = Long.valueOf(values[0]);
            weared.add(number);
            Optional<Gear> gearResult = gearRepository.findByNumber(number);
            Gear gear = gearResult.isPresent() ? gearResult.get() : new Gear();
            // Skip if the gear is already persisted
            if (gear.getId() != null && "1".equals(values[3]) == gear.getIdentified()) {
                continue;
            }
            gear.setNumber(number);
            gear.setWeared(Integer.valueOf(values[1]) > 0);
            gear.setType(GearType.fromString(values[2]));
            gear.setIdentified("1".equals(values[3]));
            String name = values[4];
            // Renamed gear
            if (name.contains("Ã")) {
                name = StringUtils.toEncodedString(name.getBytes("Windows-1252"), Charset.forName("UTF-8"));
            }
            gear.setName(name);
            gear.setTemplate(values[5]);
            gear.setDescription(values[6]);
            gear.setWeight(gear.getIdentified() ? Float.valueOf(values[7]) : 0);
            gear.setArmor(0);
            gear.setArmorM(0);
            gear.setAttack(0);
            gear.setAttackM(0);
            gear.setDamage(0);
            gear.setDamageM(0);
            gear.setDodge(0);
            gear.setDodgeM(0);
            gear.setHitPoint(0);
            gear.setMm(0);
            gear.setRm(0);
            gear.setRegeneration(0);
            gear.setView(0);
            gear.setTurn(0);
            gear.setProtection("");
            if (gear.getWeared()) {
                MountyHallUtil.setCharacteristicsFromDescription(gear, gear.getDescription());
            }
            gear.setTroll(scriptCall.getTroll());
            gearRepository.save(gear);
        }

        // Delete gear that aren't weared
        scriptCall.getTroll().getGears().stream()
            .filter(gear -> !weared.contains(gear.getNumber()))
            .forEach(gear -> gearRepository.deleteByNumber(gear.getNumber()));
    }

    public void setTrollProfilFromMHScript(ScriptCall scriptCall, Troll troll) throws MountyHallScriptException, IOException {
        String[] lines = StringUtils.split(scriptCall.getBody(), "\n");

        // Bad lines size
        if (lines.length != 1) {
            throw new MountyHallScriptException("Réponse du script MountyHall incorrect !");
        }

        scriptCall.setSuccessful(true);
        scriptCallRepository.save(scriptCall);
        String[] values = StringUtils.split(lines[0], ";");

        // numéro; Nom; Race; Niveau; Date d'inscription ; E-mail ; Blason ; Intangible ; Nb mouches ; Nb kills ; Nb morts; Numéro de Guilde; Nniveau de Rang; PNJ ?
        troll.setRace(TrollRace.valueOf(values[2]));
        troll.setLevel(Integer.valueOf(values[3]));
        troll.setBirthDate(MountyHallScriptUtil.parseDate(values[4]));
        troll.setKill(Integer.valueOf(values[9]));
        troll.setDeath(Integer.valueOf(values[10]));
    }

    public void setTrollPositionFromMHScript(ScriptCall scriptCall, Troll troll) throws MountyHallScriptException, IOException {
        String[] lines = StringUtils.split(scriptCall.getBody(), "\n");

        // Bad lines size
        if (lines.length != 1) {
            throw new MountyHallScriptException("Réponse du script MountyHall incorrect !");
        }

        scriptCall.setSuccessful(true);
        scriptCallRepository.save(scriptCall);
        String[] values = StringUtils.split(lines[0], ";");

        // ID, Nom; Position X ; Position Y ; Position N ; PA Restant; DLA ; Fatigue ; Camouflage; Invisible ; Intangible ; PX; PX perso; PI; Gigots de Gob'
        troll.setName(values[1]);
        troll.setX(Integer.valueOf(values[2]));
        troll.setY(Integer.valueOf(values[3]));
        troll.setZ(Integer.valueOf(values[4]));
    }

    public void setTrollCharacteristicFromMHScript(ScriptCall scriptCall, Troll troll) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException, MountyHallScriptException, IOException {
        String[] lines = StringUtils.split(scriptCall.getBody(), "\n");

        // Bad lines size
        if (lines.length != 3) {
            throw new MountyHallScriptException("Réponse du script MountyHall incorrect !");
        }

        for (String line : lines) {
            String[] values = StringUtils.split(line, ";");

            // Bad values size
            if (values.length != 14) {
                throw new MountyHallScriptException("Réponse du script MountyHall incorrect !");
            }

            scriptCall.setSuccessful(true);
            scriptCallRepository.save(scriptCall);

            // Attaque; Esquive; Dégats; Régénération; PVMax; PVActuels; Portée deVue; RM; MM; Armure; Duree du Tour; Poids; Concentration
            Method method;

            String sufix;
            switch (values[0]) {
                case "BMM":
                    sufix = "M";
                    break;
                case "BMP":
                    sufix = "P";
                    break;
                default:
                    sufix = "";
            }

            // Set characteristics of the troll
            for (int i = 0; i < MountyHallUtil.methodsByName.length; ++i) {
                // CurrentHitPoint and Focus are skipped for BMP/BMM
                if (StringUtils.isNotEmpty(sufix) && (i == 5 || i == 12)) {
                    continue;
                }
                // Weight is skipped for BMM
                if ("BMM".equals(values[0]) && i == 11) {
                    continue;
                }
                // Turn is skipped for BMP
                if ("BMP".equals(values[0]) && i == 10) {
                    continue;
                }
                String methodName = "set" + MountyHallUtil.methodsByName[i] + sufix;
                // Turn replace weight for BMM
                if ("BMM".equals(values[0]) && i == 10) {
                    methodName = "setWeight" + sufix;
                }
                if (!"Turn".equals(MountyHallUtil.methodsByName[i]) && !"Weight".equals(MountyHallUtil.methodsByName[i])) {
                    method = troll.getClass().getMethod(methodName, Integer.class);
                    method.invoke(troll, Integer.parseInt(values[i + 1]));
                } else {
                    method = troll.getClass().getMethod(methodName, Float.class);
                    method.invoke(troll, Float.parseFloat(values[i + 1]));
                }
            }
        }
    }

    /**
     * Update every troll's informations one time per day at midnight.
     */
    @Scheduled(cron = "0 0 0 * * ?")
    public void dailyTrollsUpdate() {
        log.debug("START UPDATING TROLLS");
        trollRepository.findAll().stream().forEach(troll -> {
            try {
                createUpdateTroll(troll);
            } catch (IOException | MountyHubException | MountyHallScriptException e) {
                log.debug("FAIL TO UPDATE TROLL " + troll.getId() + " : " + e.getMessage());
            }
        });
        log.debug("END UPDATING TROLLS");
    }

    public ProfilDTO getPrivateProfil() throws MountyHubException {
        User user = userService.getUserWithAuthorities();
        ProfilDTO profil = new ProfilDTO();

        if (user.getTroll() == null) {
            return profil;
        }

        Troll troll = trollRepository.findOne(user.getTroll().getId());
        BeanUtils.copyProperties(troll, profil);

        // Additionnal fields
        profil.setPercentHitPoint(100 * profil.getCurrentHitPoint() / (profil.getHitPoint() + profil.getHitPointP() + profil.getHitPointM()));
        profil.setBirthDateFormatted(DateUtil.formatZonedDate(troll.getBirthDate()));

        // Gears
        List<GearDTO> gearDTOs = troll.getGears().stream().map(gear -> {
            GearDTO dto = new GearDTO();
            BeanUtils.copyProperties(gear, dto);
            return dto;
        }).collect(Collectors.toList());
        profil.setGears(gearDTOs);

        // Turn, bonus/malus, weight, wounds and total turn
        Duration turn = DateUtil.getDurationFromFloatMinutes(profil.getTurn());
        profil.setTurnFormatted(DateUtil.formatDuration(turn));
        Duration bonusMalus = DateUtil.getDurationFromFloatMinutes(profil.getWeightM());
        if (!bonusMalus.isZero()) {
            profil.setBonusMalusTimeFormatted(DateUtil.formatDuration(bonusMalus));
        }
        Duration weight = DateUtil.getDurationFromFloatMinutes(profil.getWeightP());
        if (!weight.isZero()) {
            profil.setWeightTimeFormatted(DateUtil.formatDuration(weight));
        }
        Duration wounds = DateUtil.getDurationFromFloatMinutes(250F / profil.getHitPoint() * (profil.getHitPoint() + profil.getHitPointP() + profil.getHitPointM() - profil.getCurrentHitPoint()));
        if (!wounds.isZero()) {
            profil.setWoundsTimeFormatted(DateUtil.formatDuration(wounds));
        }
        profil.setTurnTotalFormatted(DateUtil.formatDuration(turn.plus(weight).plus(bonusMalus).plus(wounds)));

        // Scripts call per day
        ZonedDateTime yesterday = ZonedDateTime.now().minusDays(1);
        Map<ScriptType, Long> groupedScripts = troll.getScriptCalls().stream()
            .filter(script -> script.getSuccessful() && script.getDateCalled().isAfter(yesterday))
            .collect(Collectors.groupingBy(ScriptCall::getType, Collectors.counting()));

        profil.setScriptAppelByDay(groupedScripts.getOrDefault(ScriptType.APPEL, 0L));
        profil.setScriptDynamiqueByDay(groupedScripts.getOrDefault(ScriptType.DYNAMIQUE, 0L));
        profil.setScriptMessageByDay(groupedScripts.getOrDefault(ScriptType.MESSAGE, 0L));
        profil.setScriptStatiqueByDay(groupedScripts.getOrDefault(ScriptType.STATIQUE, 0L));

        // Last scripts call by type
        Map<ScriptName, List<ScriptCall>> lastGroupedScripts = troll.getScriptCalls().stream()
            .filter(ScriptCall::getSuccessful)
            .sorted((sc1, sc2) -> sc2.getDateCalled().compareTo(sc1.getDateCalled()))
            .collect(Collectors.groupingBy(ScriptCall::getName));
        profil.setLastCharacteristicDate(DateUtil.formatPassedDate(lastGroupedScripts.get(ScriptName.SP_Caract).get(0).getDateCalled()));
        profil.setLastGearDate(DateUtil.formatPassedDate(lastGroupedScripts.get(ScriptName.SP_Equipement).get(0).getDateCalled()));
        profil.setLastStateDate(DateUtil.formatPassedDate(lastGroupedScripts.get(ScriptName.SP_Profil3).get(0).getDateCalled()));

        return profil;
    }

    public void deleteTroll() throws MountyHubException {
        User currentUser = userService.getUserWithAuthorities();

        // No troll linked to user
        if (currentUser.getTroll() == null) {
            throw new MountyHubException("Votre utilisateur n'a pas de troll lié !");
        }

        // Set troll deleted and remove it from the user
        currentUser.getTroll().setDeleted(true);
        trollRepository.save(currentUser.getTroll());
        currentUser.setTroll(null);
        userRepository.save(currentUser);
    }

    public void refreshTroll(ScriptName scriptName) throws IOException, MountyHallScriptException, MountyHubException {
        Troll troll = userService.getUserWithAuthorities().getTroll();

        if (troll == null) {
            throw new MountyHubException("Vous n'avez pas de Troll !");
        }

        switch (scriptName) {
            case SP_Profil3:
                try {
                    ScriptCall caractCall = MountyHallScriptUtil.createScriptCall(ScriptName.SP_Caract);
                    ScriptCall positionCall = MountyHallScriptUtil.createScriptCall(ScriptName.SP_Profil3);
                    scriptCallService.callScript(caractCall, troll);
                    setTrollCharacteristicFromMHScript(caractCall, troll);
                    scriptCallService.callScript(positionCall, troll);
                    setTrollPositionFromMHScript(positionCall, troll);
                    trollRepository.save(troll);
                    scriptCallRepository.save(caractCall);
                    scriptCallRepository.save(positionCall);
                } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                    throw new MountyHallScriptException("Erreur lors du parsage du script MountyHall !", e);
                }
                break;
            case SP_Equipement:
                try {
                    ScriptCall gearCall = MountyHallScriptUtil.createScriptCall(ScriptName.SP_Equipement);
                    scriptCallService.callScript(gearCall, troll);
                    setTrollGearFromMHScript(gearCall);
                } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                    throw new MountyHallScriptException("Erreur lors du parsage du script MountyHall !", e);
                }
                break;
            default:
                throw new MountyHubException("Type de refresh non-implémenté !");
        }

    }
}
