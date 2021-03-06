package org.akaza.openclinica.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jxl.demo.CSV;
import net.sf.saxon.type.ItemType;
import org.akaza.openclinica.ParticipateInviteEnum;
import org.akaza.openclinica.ParticipateInviteStatusEnum;
import org.akaza.openclinica.bean.admin.CRFBean;
import org.akaza.openclinica.bean.core.Role;
import org.akaza.openclinica.bean.login.ParticipantDTO;
import org.akaza.openclinica.bean.login.UserAccountBean;
import org.akaza.openclinica.bean.managestudy.*;
import org.akaza.openclinica.bean.submit.*;
import org.akaza.openclinica.bean.submit.crfdata.*;
import org.akaza.openclinica.controller.dto.*;
import org.akaza.openclinica.controller.helper.RestfulServiceHelper;
import org.akaza.openclinica.controller.openrosa.OpenRosaSubmissionController;
import org.akaza.openclinica.core.EmailEngine;
import org.akaza.openclinica.core.form.StringUtil;
import org.akaza.openclinica.dao.admin.CRFDAO;
import org.akaza.openclinica.dao.core.CoreResources;
import org.akaza.openclinica.dao.hibernate.*;
import org.akaza.openclinica.dao.managestudy.*;
import org.akaza.openclinica.dao.submit.FormLayoutDAO;
import org.akaza.openclinica.dao.submit.ItemDAO;
import org.akaza.openclinica.dao.submit.ItemGroupDAO;
import org.akaza.openclinica.domain.EventCRFStatus;
import org.akaza.openclinica.domain.Status;
import org.akaza.openclinica.domain.datamap.*;
import org.akaza.openclinica.domain.datamap.ResponseType;
import org.akaza.openclinica.domain.enumsupport.JobStatus;
import org.akaza.openclinica.domain.enumsupport.JobType;
import org.akaza.openclinica.domain.user.UserAccount;
import org.akaza.openclinica.exception.OpenClinicaException;
import org.akaza.openclinica.exception.OpenClinicaSystemException;
import org.akaza.openclinica.service.crfdata.ErrorObj;
import org.akaza.openclinica.web.rest.client.auth.impl.KeycloakClientImpl;
import org.akaza.openclinica.web.util.ErrorConstants;
import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import javax.servlet.ServletContext;
import javax.validation.Valid;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.text.DecimalFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.akaza.openclinica.domain.rule.action.NotificationActionProcessor.messageServiceUri;
import static org.akaza.openclinica.domain.rule.action.NotificationActionProcessor.sbsUrl;

/**
 * This Service class is used with View Study Subject Page
 *
 * @author joekeremian
 */

@Service( "importService" )
public class ImportServiceImpl implements ImportService {
    protected final Logger logger = LoggerFactory.getLogger(getClass().getName());


    @Autowired
    UserAccountDao userAccountDao;

    @Autowired
    StudyDao studyDao;

    @Autowired
    StudySubjectDao studySubjectDao;

    @Autowired
    StudyEventDao studyEventDao;

    @Autowired
    EventCrfDao eventCrfDao;

    @Autowired
    ItemDataDao itemDataDao;

    @Autowired
    StudyEventDefinitionDao studyEventDefinitionDao;

    @Autowired
    CrfDao crfDao;

    @Autowired
    CrfVersionDao crfVersionDao;

    @Autowired
    CompletionStatusDao completionStatusDao;

    @Autowired
    EventDefinitionCrfDao eventDefinitionCrfDao;

    @Autowired
    FormLayoutDao formLayoutDao;

    @Autowired
    ItemGroupDao itemGroupDao;

    @Autowired
    ItemGroupMetadataDao itemGroupMetadataDao;

    @Autowired
    ItemDao itemDao;

    @Autowired
    ValidateService validateService;

    @Autowired
    UtilService utilService;

    @Autowired
    UserService userService;

    @Autowired
    JobService jobService;

    @Autowired
    VersioningMapDao versioningMapDao;

    @Autowired
    OpenRosaSubmissionController openRosaSubmissionController;

    public static final String COMMON = "common";
    public static final String UNSCHEDULED = "unscheduled";
    public static final String SEPERATOR = ",";
    public static final String BULK_JOBS = "bulk_jobs";
    public static final String DASH = "-";
    public static final String UNDERSCORE = "_";
    public static final String INITIAL_DATA_ENTRY = "initial data entry";
    public static final String DATA_ENTRY_COMPLETE = "data entry complete";
    public static final String COMPLETE = "complete";
    public static final String FAILED = "Failed";
    public static final String INSERTED = "Inserted";
    public static final String UPDATED = "Updated";
    public static final String SKIPPED = "Skipped";


    @Transactional
    public void validateAndProcessDataImport(ODMContainer odmContainer, String studyOid, String siteOid, UserAccountBean userAccountBean, String schema, JobDetail jobDetail) {
        CoreResources.setRequestSchema(schema);
        Study tenantStudy = null;
        if (siteOid != null) {
            tenantStudy = studyDao.findByOcOID(siteOid);
        } else {
            tenantStudy = studyDao.findByOcOID(studyOid);
        }
        if (tenantStudy == null) {
            logger.error("Study {} Not Valid", tenantStudy.getOc_oid());
        }

        List<DataImportReport> dataImportReports = new ArrayList<>();
        DataImportReport dataImportReport = null;

        UserAccount userAccount = userAccountDao.findById(userAccountBean.getId());
        String uniqueIdentifier = tenantStudy.getStudy() == null ? tenantStudy.getUniqueIdentifier() : tenantStudy.getStudy().getUniqueIdentifier();
        String envType = tenantStudy.getStudy() == null ? tenantStudy.getEnvType().toString() : tenantStudy.getStudy().getEnvType().toString();

        String fileName = uniqueIdentifier + DASH + envType + UNDERSCORE + JobType.XML_IMPORT + new SimpleDateFormat("_yyyy-MM-dd-hhmmssS'.txt'").format(new Date());
        logger.debug("Job Filename is : {}", fileName);

        ArrayList<SubjectDataBean> subjectDataBeans = odmContainer.getCrfDataPostImportContainer().getSubjectData();
        for (SubjectDataBean subjectDataBean : subjectDataBeans) {
            if (subjectDataBean.getSubjectOID() != null)
                subjectDataBean.setSubjectOID(subjectDataBean.getSubjectOID().toUpperCase());
            StudySubject studySubject = null;
            StudySubject studySubject02 = null;


            if (subjectDataBean.getSubjectOID() == null && subjectDataBean.getStudySubjectID() == null) {
                dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), null, null, null, null, null, null, FAILED, "errorCode.participantNotFound ");
                dataImportReports.add(dataImportReport);
                logger.info("Participant SubjectKey and StudySubjectID are null ");
                continue;
            } else if (subjectDataBean.getSubjectOID() != null && subjectDataBean.getStudySubjectID() == null) {
                studySubject = studySubjectDao.findByOcOID(subjectDataBean.getSubjectOID());
                if (studySubject != null)
                    studySubject = studySubjectDao.findByLabelAndStudyOrParentStudy(studySubject.getLabel(), tenantStudy);

                if (studySubject == null) {
                    dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), null, null, null, null, null, null, FAILED, "errorCode.participantNotFound ");
                    dataImportReports.add(dataImportReport);
                    logger.error("Participant SubjectKey {} Not Found", subjectDataBean.getSubjectOID());
                    continue;
                }
            } else if (subjectDataBean.getSubjectOID() == null && subjectDataBean.getStudySubjectID() != null) {
                try {
                    studySubject = studySubjectDao.findByLabelAndStudyOrParentStudy(subjectDataBean.getStudySubjectID(), tenantStudy);

                    if (studySubject == null) {
                        dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), null, null, null, null, null, null, FAILED, "errorCode.participantNotFound ");
                        dataImportReports.add(dataImportReport);
                        logger.error("Participant StudySubjectID {} Not Found", subjectDataBean.getStudySubjectID());
                        continue;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), null, null, null, null,
                            null, null, FAILED, "errorCode.multipleParticipantsFound ");
                    dataImportReports.add(dataImportReport);
                    logger.error("multipleParticipantsFound {}", e.getMessage());
                    continue;
                }

            } else if (subjectDataBean.getSubjectOID() != null && subjectDataBean.getStudySubjectID() != null) {
                studySubject = studySubjectDao.findByOcOID(subjectDataBean.getSubjectOID());
                if (studySubject != null)
                    studySubject = studySubjectDao.findByLabelAndStudyOrParentStudy(studySubject.getLabel(), tenantStudy);
                studySubject02 = studySubjectDao.findByLabelAndStudyOrParentStudy(subjectDataBean.getStudySubjectID(), tenantStudy);

                if (studySubject == null && studySubject02 == null) {
                    dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), null, null, null, null, null, null, FAILED, "errorCode.participantNotFound ");
                    dataImportReports.add(dataImportReport);
                    logger.error("Participant StudySubjectID {} Not Found", subjectDataBean.getStudySubjectID());
                    continue;
                } else if (studySubject == null || studySubject02 == null || (studySubject != null && studySubject02 != null && studySubject.getStudySubjectId() != studySubject02.getStudySubjectId())) {
                    dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), null, null, null, null, null, null, FAILED, "errorCode.participantIdentiersMismatch ");
                    dataImportReports.add(dataImportReport);
                    logger.error("Participant Identifiers {} {} mismatch", subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID());
                    continue;
                }
            }
            subjectDataBean.setSubjectOID(studySubject.getOcOid());
            subjectDataBean.setStudySubjectID(studySubject.getLabel());

            ArrayList<StudyEventDataBean> studyEventDataBeans = subjectDataBean.getStudyEventData();
            for (StudyEventDataBean studyEventDataBean : studyEventDataBeans) {
                if (studyEventDataBean.getStudyEventOID() != null)
                    studyEventDataBean.setStudyEventOID(studyEventDataBean.getStudyEventOID().toUpperCase());
                // OID is missing
                if (studyEventDataBean.getStudyEventOID() == null) {
                    dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), null, null, null, null, null, FAILED, "errorCode.invalidStudyEventOID ");
                    dataImportReports.add(dataImportReport);
                    logger.error("StudEventOID {} is not valid", studyEventDataBean.getStudyEventOID());
                    continue;
                }

                // StudyEventDefinition invalid OID and Archived
                StudyEventDefinition studyEventDefinition = studyEventDefinitionDao.findByOcOID(studyEventDataBean.getStudyEventOID());
                if (studyEventDefinition == null || (studyEventDefinition != null && !studyEventDefinition.getStatus().equals(Status.AVAILABLE))) {
                    dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), null, null, null, null, null, FAILED, "errorCode.invalidStudyEventOID ");
                    dataImportReports.add(dataImportReport);
                    logger.error("StudEventOID {} is not valid or Archived", studyEventDataBean.getStudyEventOID());
                    continue;
                }

                if (studyEventDataBean.getEndDate() != null) {
                    ErrorObj endDateErrorObj = validateForDate(studyEventDataBean.getEndDate());
                    if (endDateErrorObj != null) {
                        dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), null, null, null, null, FAILED, "errorCode.eventNotScheduled.invalidEndDate");
                        dataImportReports.add(dataImportReport);
                        logger.error("StudEventOID {} eventNotScheduled.invalidEndDate", studyEventDataBean.getStudyEventOID());
                        continue;
                    }
                }


                // Study Event Repeat key is not an int number
                if (studyEventDataBean.getStudyEventRepeatKey() != null) {
                    try {
                        Integer.parseInt(studyEventDataBean.getStudyEventRepeatKey());
                    } catch (NumberFormatException nfe) {
                        nfe.getStackTrace();
                        dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), studySubject.getLabel(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), null, null, null, null, FAILED, "errorCode.repeatKeyNotValid ");
                        dataImportReports.add(dataImportReport);
                        logger.error("StudyEventRepeatKey {} is not Valid Integer", studyEventDataBean.getStudyEventRepeatKey());
                        continue;
                    }
                }
                int maxSeOrdinal = studyEventDao.findMaxOrdinalByStudySubjectStudyEventDefinition(studySubject.getStudySubjectId(), studyEventDefinition.getStudyEventDefinitionId());

                StudyEvent studyEvent = null;
                if (studyEventDataBean.getStudyEventRepeatKey() != null) {  // Repeat Key present
                    studyEvent = studyEventDao.fetchByStudyEventDefOIDAndOrdinal(studyEventDataBean.getStudyEventOID(), Integer.parseInt(studyEventDataBean.getStudyEventRepeatKey()), studySubject.getStudySubjectId());
                    // repeat key present,not scheduled, common event , verify key too Large , schedule
                    if (studyEvent == null && studyEventDefinition.getType().equals(COMMON)) {
                        if (Integer.parseInt(studyEventDataBean.getStudyEventRepeatKey()) != (maxSeOrdinal + 1)) {
                            dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), null, null, null, null, FAILED, "errorCode.studyEventRepeatKeyTooLarge ");
                            dataImportReports.add(dataImportReport);
                            logger.error("RepeatKey {} too large,  is not next available repeat number", studyEventDataBean.getStudyEventRepeatKey());
                            continue;
                        }

                        studyEvent = createStudyEvent(studySubject, studyEventDefinition, Integer.parseInt(studyEventDataBean.getStudyEventRepeatKey()), userAccount, null, null);
                        studyEvent = studyEventDao.saveOrUpdate(studyEvent);
                        logger.debug("Scheduling new Common Event Id {} ", studyEvent.getStudyEventId());
                        // repeat key present,not scheduled, visit event Repeating ,start date present , verify key too Large ,verify startdate valid ,schedule
                    } else if (studyEvent == null && studyEventDefinition.getType().equals(UNSCHEDULED) && studyEventDefinition.getRepeating() && studyEventDataBean.getStartDate() != null) {
                        if (Integer.parseInt(studyEventDataBean.getStudyEventRepeatKey()) != (maxSeOrdinal + 1)) {
                            dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), null, null, null, null, FAILED, "errorCode.studyEventRepeatKeyTooLarge ");
                            dataImportReports.add(dataImportReport);
                            logger.error("RepeatKey {} studyEventRepeatKeyTooLarge, is not next available repeat number", studyEventDataBean.getStudyEventRepeatKey());
                            continue;
                        }
                        ErrorObj startDateErrorObj = validateForDate(studyEventDataBean.getStartDate());
                        if (startDateErrorObj != null) {
                            dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), null, null, null, null, FAILED, "errorCode.eventNotScheduled.invalidStartDate");
                            dataImportReports.add(dataImportReport);
                            logger.error("StudEventOID {} eventNotScheduled.invalidStartDate", studyEventDataBean.getStudyEventOID());
                            continue;
                        }

                        studyEvent = createStudyEvent(studySubject, studyEventDefinition, Integer.parseInt(studyEventDataBean.getStudyEventRepeatKey()), userAccount, studyEventDataBean.getStartDate(), studyEventDataBean.getEndDate());
                        studyEvent = studyEventDao.saveOrUpdate(studyEvent);
                        logger.debug("Scheduling new Visit Base Repeating Event ID {}", studyEvent.getStudyEventId());

                        // repeat key present,not scheduled, visit event Repeating ,start date null , Can't schedule
                    } else if (studyEvent == null && studyEventDefinition.getType().equals(UNSCHEDULED) && studyEventDefinition.getRepeating() && studyEventDataBean.getStartDate() == null) {
                        dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), null, null, null, null, FAILED, "errorCode.eventNotScheduled.startDateMissing");
                        dataImportReports.add(dataImportReport);
                        logger.error("StudEventOID {} eventNotScheduled.invalidStartDate", studyEventDataBean.getStudyEventOID());

                        continue;

                        // repeat key present, not scheduled,visit event Non Repeating ,start date present ,verify repeat key=1 ,verify startdate valid ,schedule
                    } else if (studyEvent == null && studyEventDefinition.getType().equals(UNSCHEDULED) && !studyEventDefinition.getRepeating() && studyEventDataBean.getStartDate() != null) {

                        if (Integer.parseInt(studyEventDataBean.getStudyEventRepeatKey()) != 1) {
                            dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), null, null, null, null, FAILED, "errorCode.repeatKeyNot1 ");
                            dataImportReports.add(dataImportReport);
                            logger.error("RepeatKey {} for non repeating event is not Equal to 1", studyEventDataBean.getStudyEventRepeatKey());
                            continue;
                        }

                        ErrorObj startDateErrorObj = validateForDate(studyEventDataBean.getStartDate());
                        if (startDateErrorObj != null) {
                            dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), null, null, null, null, FAILED, "errorCode.eventNotScheduled.invalidStartDate");
                            dataImportReports.add(dataImportReport);
                            logger.error("StudEventOID {} eventNotScheduled.invalidStartDate", studyEventDataBean.getStudyEventOID());
                            continue;
                        }

                        studyEvent = createStudyEvent(studySubject, studyEventDefinition, Integer.parseInt(studyEventDataBean.getStudyEventRepeatKey()), userAccount, studyEventDataBean.getStartDate(), studyEventDataBean.getEndDate());
                        studyEvent = studyEventDao.saveOrUpdate(studyEvent);
                        logger.debug("Scheduling new Visit Base Non Repeating Event Id {}", studyEvent.getStudyEventId());

                        // repeat key present,not scheduled, visit event Non Repeating ,start date null , Can't schedule
                    } else if (studyEvent == null && studyEventDefinition.getType().equals(UNSCHEDULED) && !studyEventDefinition.getRepeating() && studyEventDataBean.getStartDate() == null) {
                        dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), null, null, null, null, FAILED, "errorCode.eventNotScheduled.startDateMissing");
                        dataImportReports.add(dataImportReport);
                        logger.error("StudEventOID {} eventNotScheduled.startDateMissing", studyEventDataBean.getStudyEventOID());
                        continue;


                    }


                } else { // Repeat Key is missing
                    // Repeat Key missing,not scheduled, common event , assign repeat, schedule
                    if (studyEvent == null && studyEventDefinition.getType().equals(COMMON)) {
                        studyEventDataBean.setStudyEventRepeatKey(String.valueOf(maxSeOrdinal + 1));

                        // assign new repeat Key
                        studyEvent = createStudyEvent(studySubject, studyEventDefinition, maxSeOrdinal + 1, userAccount, null, null);
                        studyEvent = studyEventDao.saveOrUpdate(studyEvent);
                        logger.debug("Scheduling new Common Event Id {}", studyEvent.getStudyEventId());
                        // Repeat Key missing, not scheduled,visit event Repeating, start date not null,validate start date , assign repeat, schedule
                    } else if (studyEvent == null && studyEventDefinition.getType().equals(UNSCHEDULED) && studyEventDefinition.getRepeating() && studyEventDataBean.getStartDate() != null) {
                        studyEventDataBean.setStudyEventRepeatKey(String.valueOf(maxSeOrdinal + 1));
                        ErrorObj startDateErrorObj = validateForDate(studyEventDataBean.getStartDate());
                        if (startDateErrorObj != null) {
                            dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), null, null, null, null, FAILED, "errorCode.eventNotScheduled.invalidStartDate");
                            dataImportReports.add(dataImportReport);
                            logger.error("StudEventOID {} eventNotScheduled.invalidStartDate", studyEventDataBean.getStudyEventOID());
                            continue;
                        }

                        studyEvent = createStudyEvent(studySubject, studyEventDefinition, maxSeOrdinal + 1, userAccount, studyEventDataBean.getStartDate(), studyEventDataBean.getEndDate());
                        studyEvent = studyEventDao.saveOrUpdate(studyEvent);
                        logger.debug("Scheduling new Visit Base Repeating Event ID {}", studyEvent.getStudyEventId());

                        // Repeat Key missing, not scheduled,visit event Repeating, start date  null, reject
                    } else if (studyEvent == null && studyEventDefinition.getType().equals(UNSCHEDULED) && studyEventDefinition.getRepeating() && studyEventDataBean.getStartDate() == null) {
                        dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), null, null, null, null, FAILED, "errorCode.eventNotScheduled.startDateMissing");
                        dataImportReports.add(dataImportReport);
                        logger.error("StudEventOID {} eventNotScheduled.startDateMissing", studyEventDataBean.getStudyEventOID());
                        continue;
                    }


                    // Repeat Key missing,visit event Non Repeating,

                    if (studyEventDefinition.getType().equals(UNSCHEDULED) && !studyEventDefinition.getRepeating()) {
                        studyEventDataBean.setStudyEventRepeatKey("1");
                        studyEvent = studyEventDao.fetchByStudyEventDefOIDAndOrdinal(studyEventDataBean.getStudyEventOID(), Integer.parseInt(studyEventDataBean.getStudyEventRepeatKey()), studySubject.getStudySubjectId());

                        // Repeat Key missing, not scheduled,visit event Non Repeating,start date not null,validate start date , assign repeat 1 , schedule
                        if (studyEvent == null && studyEventDataBean.getStartDate() != null) {
                            ErrorObj startDateErrorObj = validateForDate(studyEventDataBean.getStartDate());
                            if (startDateErrorObj != null) {
                                dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), null, null, null, null, FAILED, "errorCode.eventNotScheduled.invalidStartDate");
                                dataImportReports.add(dataImportReport);
                                logger.error("StudEventOID {} eventNotScheduled.invalidStartDate", studyEventDataBean.getStudyEventOID());
                                continue;
                            }


                            studyEvent = createStudyEvent(studySubject, studyEventDefinition, 1, userAccount, studyEventDataBean.getStartDate(), studyEventDataBean.getEndDate());
                            studyEvent = studyEventDao.saveOrUpdate(studyEvent);
                            logger.debug("Scheduling new Visit Base Repeating Event Id {}", studyEvent.getStudyEventId());

                            // Repeat Key missing,not scheduled, visit event Non Repeating, start date  null, reject
                        } else if (studyEvent == null && studyEventDataBean.getStartDate() == null) {
                            dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), null, null, null, null, FAILED, "errorCode.eventNotScheduled.startDateMissing");
                            dataImportReports.add(dataImportReport);
                            logger.error("StudEventOID {} eventNotScheduled.startDateMissing", studyEventDataBean.getStudyEventOID());
                            continue;
                        }
                    }

                }


                ArrayList<FormDataBean> formDataBeans = studyEventDataBean.getFormData();
                for (FormDataBean formDataBean : formDataBeans) {
                    if (formDataBean.getFormOID() != null)
                        formDataBean.setFormOID(formDataBean.getFormOID().toUpperCase());
                    if (formDataBean.getEventCRFStatus() != null)
                        formDataBean.setEventCRFStatus(formDataBean.getEventCRFStatus().toLowerCase());

                    if (formDataBean.getFormOID() == null) {
                        dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), formDataBean.getFormOID(), null, null, null, FAILED, "errorCode.formOIDNotFound ");
                        dataImportReports.add(dataImportReport);
                        logger.error("FormOid {} for SubjectKey {} and StudyEventOID {} is not Valid", formDataBean.getFormOID(), subjectDataBean.getSubjectOID(), studyEventDataBean.getStudyEventOID());
                        continue;
                    }


                    // Form Invalid OID
                    CrfBean crf = crfDao.findByOcOID(formDataBean.getFormOID());
                    if (crf == null || (crf != null && !crf.getStatus().equals(Status.AVAILABLE))) {
                        dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), formDataBean.getFormOID(), null, null, null, FAILED, "errorCode.formOIDNotFound ");
                        dataImportReports.add(dataImportReport);
                        logger.error("FormOid {} is not Valid or not Found", formDataBean.getFormOID());
                        continue;
                    }
                    // Form Invalid OID
                    EventDefinitionCrf edc = eventDefinitionCrfDao.findByStudyEventDefinitionIdAndCRFIdAndStudyId(studyEventDefinition.getStudyEventDefinitionId(), crf.getCrfId(),
                            tenantStudy.getStudy() == null ? tenantStudy.getStudyId() : tenantStudy.getStudy().getStudyId());
                    if (edc == null || (edc != null && !edc.getStatusId().equals(Status.AVAILABLE.getCode()))) {
                        dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), formDataBean.getFormOID(), null, null, null, FAILED, "errorCode.formOIDNotFound ");
                        dataImportReports.add(dataImportReport);
                        logger.error("FormOid {} is not Valid or not Found", formDataBean.getFormOID());
                        continue;

                    }

                    if (formDataBean.getFormLayoutName() == null) {
                        formDataBean.setFormLayoutName(edc.getFormLayout().getName());
                    }

                    // FormLayout Invalid OID
                    FormLayout formLayout = formLayoutDao.findByNameCrfId(formDataBean.getFormLayoutName(), crf.getCrfId());
                    if (formLayout == null || (formLayout != null && !formLayout.getStatus().equals(Status.AVAILABLE))) {
                        dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), formDataBean.getFormOID(), null, null, null, FAILED, "errorCode.formLayoutOIDNotFound ");
                        dataImportReports.add(dataImportReport);
                        logger.error("FormLayoutOid {} is not Valid or not Found", formDataBean.getFormLayoutName());
                        continue;

                    }

                    // Form Status is null , then Form has Initial Data Entry Status
                    if (formDataBean.getEventCRFStatus() == null) {
                        formDataBean.setEventCRFStatus(INITIAL_DATA_ENTRY);
                    }

                    // Form Status is not acceptable
                    if (!formDataBean.getEventCRFStatus().equals(INITIAL_DATA_ENTRY) &&
                            !formDataBean.getEventCRFStatus().equals(DATA_ENTRY_COMPLETE) &&
                            !formDataBean.getEventCRFStatus().equals(COMPLETE)) {
                        dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), formDataBean.getFormOID(), null, null, null, FAILED, "errorCode.formStatusNotValid ");
                        dataImportReports.add(dataImportReport);
                        logger.error("Form Status {}  is not Valid", formDataBean.getEventCRFStatus());
                        continue;
                    }

                    // Event Crf has status complete
                    EventCrf eventCrf = eventCrfDao.findByStudyEventIdStudySubjectIdFormLayoutId(studyEvent.getStudyEventId(), studySubject.getStudySubjectId(), formLayout.getFormLayoutId());
                    if (eventCrf != null && eventCrf.getStatusId().equals(Status.UNAVAILABLE.getCode())) {
                        dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), formDataBean.getFormOID(), null, null, null, FAILED, "errorCode.formAlreadyComplete ");
                        dataImportReports.add(dataImportReport);
                        logger.debug("Form {}  already complete", formDataBean.getFormOID());
                        continue;
                    }

                    if (eventCrf == null) {
                        eventCrf = createEventCrf(studySubject, studyEvent, formLayout, userAccount);
                        eventCrf = eventCrfDao.saveOrUpdate(eventCrf);
                        logger.debug("new EventCrf Id {} is created  ", eventCrf.getEventCrfId());

                        studyEvent = updateStudyEvent(studyEvent, userAccount);
                        studyEvent = studyEventDao.saveOrUpdate(studyEvent);
                        logger.debug("Study Event Id {} is updated", studyEvent.getStudyEventId());
                    }

                    ArrayList<ImportItemGroupDataBean> itemGroupDataBeans = formDataBean.getItemGroupData();

                    int itemCountInFormData = 0;
                    int itemInsertedUpdatedCountInFrom = 0;
                    int itemInsertedUpdatedSkippedCountInFrom = 0;
                    for (ImportItemGroupDataBean itemGroupDataBean : itemGroupDataBeans) {
                        itemCountInFormData = itemCountInFormData + itemGroupDataBean.getItemData().size();
                    }

                    for (ImportItemGroupDataBean itemGroupDataBean : itemGroupDataBeans) {
                        if (itemGroupDataBean.getItemGroupOID() != null)
                            itemGroupDataBean.setItemGroupOID(itemGroupDataBean.getItemGroupOID().toUpperCase());
                        if (itemGroupDataBean.getItemGroupOID() == null) {
                            dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), formDataBean.getFormOID(), itemGroupDataBean.getItemGroupOID(), null, null, FAILED, "errorCode.itemGroupOIDNotFound ");
                            dataImportReports.add(dataImportReport);
                            logger.error("ItemGroupOid {} is not Valid or not found", itemGroupDataBean.getItemGroupOID());
                            continue;
                        }

                        //Item Group invalid Oid
                        ItemGroup itemGroup = itemGroupDao.findByOcOID(itemGroupDataBean.getItemGroupOID());
                        if (itemGroup == null || (itemGroup != null && !itemGroup.getStatus().equals(Status.AVAILABLE))) {
                            dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), formDataBean.getFormOID(), itemGroupDataBean.getItemGroupOID(), null, null, FAILED, "errorCode.itemGroupOIDNotFound ");
                            dataImportReports.add(dataImportReport);
                            logger.error("ItemGroupOid {} is not Valid or not found", itemGroupDataBean.getItemGroupOID());
                            continue;
                        }
                        //Item Group invalid Oid
                        ItemGroup itmGroup = itemGroupDao.findByNameCrfId(itemGroup.getName(), crf);
                        if (itmGroup == null) {
                            dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), formDataBean.getFormOID(), itemGroupDataBean.getItemGroupOID(), null, null, FAILED, "errorCode.itemGroupOIDNotFound ");
                            dataImportReports.add(dataImportReport);
                            logger.error("ItemGroupOid {} is not Valid or not found", itemGroupDataBean.getItemGroupOID());
                            continue;
                        }

                        ArrayList<ImportItemDataBean> itemDataBeans = itemGroupDataBean.getItemData();

                        int highestGroupOrdinal = 0;

                        List<ItemGroupMetadata> igms = itemGroup.getItemGroupMetadatas();
                        for (ItemGroupMetadata igm : igms) {
                            int maxRepeatGroup = itemDataDao.getMaxGroupRepeat(eventCrf.getEventCrfId(), igm.getItem().getItemId());
                            if (maxRepeatGroup > highestGroupOrdinal)
                                highestGroupOrdinal = maxRepeatGroup;
                        }


                        //Missing Item Group Repeat Key
                        if (itemGroupDataBean.getItemGroupRepeatKey() == null && !itemGroup.getItemGroupMetadatas().get(0).isRepeatingGroup()) {
                            itemGroupDataBean.setItemGroupRepeatKey("1");
                        } else if (itemGroupDataBean.getItemGroupRepeatKey() == null && itemGroup.getItemGroupMetadatas().get(0).isRepeatingGroup()) {
                            itemGroupDataBean.setItemGroupRepeatKey(String.valueOf(highestGroupOrdinal + 1));
                        }

                        if (Integer.parseInt(itemGroupDataBean.getItemGroupRepeatKey()) > (highestGroupOrdinal + 1)) {
                            dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), formDataBean.getFormOID(), itemGroupDataBean.getItemGroupOID(), itemGroupDataBean.getItemGroupRepeatKey(), null, FAILED, "errorCode.itemGroupRepeatKeyTooLarge");
                            dataImportReports.add(dataImportReport);
                            logger.error("ItemGroupRepeatKey {} is too large", itemGroupDataBean.getItemGroupRepeatKey());
                            continue;
                        }


                        // Item Group Repeat key is not an int number
                        try {
                            int groupRepeatKey = Integer.parseInt(itemGroupDataBean.getItemGroupRepeatKey());
                        } catch (NumberFormatException nfe) {
                            nfe.getStackTrace();
                            dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), formDataBean.getFormOID(), itemGroupDataBean.getItemGroupOID(), itemGroupDataBean.getItemGroupRepeatKey(), null, FAILED, "errorCode.groupRepeatKeyNotValid ");
                            dataImportReports.add(dataImportReport);
                            logger.error("ItemGroupRepeatKey {} is not valid Integer", itemGroupDataBean.getItemGroupRepeatKey());
                            continue;
                        }


                        // Item Group Repeat key is Less than 1
                        if (Integer.parseInt(itemGroupDataBean.getItemGroupRepeatKey()) < 1) {
                            dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), formDataBean.getFormOID(), itemGroupDataBean.getItemGroupOID(), itemGroupDataBean.getItemGroupRepeatKey(), null, FAILED, "errorCode.groupRepeatKeyLessThanOne ");
                            dataImportReports.add(dataImportReport);
                            logger.error("ItemGroupRepeatKey {} is Less than 1", itemGroupDataBean.getItemGroupRepeatKey());
                            continue;
                        }


                        //Item Group is Non Repeating and Repeat key is >1
                        if (!itemGroup.getItemGroupMetadatas().get(0).isRepeatingGroup() && Integer.parseInt(itemGroupDataBean.getItemGroupRepeatKey()) > 1) {
                            logger.debug("RepeatKey {} for SubjectKey {} and StudyEventOID {} is Larger Than 1", studyEventDataBean.getStudyEventRepeatKey(), subjectDataBean.getSubjectOID(), studyEventDataBean.getStudyEventOID());
                            dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), formDataBean.getFormOID(), itemGroupDataBean.getItemGroupOID(), itemGroupDataBean.getItemGroupRepeatKey(), null, FAILED, "errorCode.repeatKeyLargerThanOne ");
                            dataImportReports.add(dataImportReport);
                            logger.error("ItemGroupRepeatKey {} is Greater than 1", itemGroupDataBean.getItemGroupRepeatKey());
                            continue;
                        }


                        for (ImportItemDataBean itemDataBean : itemDataBeans) {
                            if (itemDataBean.getItemOID() != null)
                                itemDataBean.setItemOID(itemDataBean.getItemOID().toUpperCase());
                            if (itemDataBean.getItemOID() == null) {
                                dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), formDataBean.getFormOID(), itemGroupDataBean.getItemGroupOID(), itemGroupDataBean.getItemGroupRepeatKey(), itemDataBean.getItemOID(), FAILED, "errorCode.itemNotFound ");
                                dataImportReports.add(dataImportReport);
                                logger.error("Item {} is not found or invalid", itemDataBean.getItemOID());
                                continue;
                            }

                            Item item = itemDao.findByOcOID(itemDataBean.getItemOID());

                            // ItemOID is not valid
                            if (item == null || (item != null && !item.getStatus().equals(Status.AVAILABLE))) {
                                dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), formDataBean.getFormOID(), itemGroupDataBean.getItemGroupOID(), itemGroupDataBean.getItemGroupRepeatKey(), itemDataBean.getItemOID(), FAILED, "errorCode.itemNotFound ");
                                dataImportReports.add(dataImportReport);
                                logger.error("Item {} is not found or invalid", itemDataBean.getItemOID());
                                continue;
                            }
                            Item itm = itemDao.findByNameCrfId(item.getName(), crf.getCrfId());
                            // ItemOID is not valid
                            if (itm == null) {
                                dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), formDataBean.getFormOID(), itemGroupDataBean.getItemGroupOID(), itemGroupDataBean.getItemGroupRepeatKey(), itemDataBean.getItemOID(), FAILED, "errorCode.itemNotFound ");
                                dataImportReports.add(dataImportReport);
                                logger.error("Item {} is not found or invalid", itemDataBean.getItemOID());
                                continue;
                            }


                            if (itemDataBean.getValue() == null) {
                                dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), formDataBean.getFormOID(), itemGroupDataBean.getItemGroupOID(), itemGroupDataBean.getItemGroupRepeatKey(), itemDataBean.getItemOID(), FAILED, "errorCode.missingValue ");
                                dataImportReports.add(dataImportReport);
                                logger.error("Item {} value is missing", itemDataBean.getItemOID());
                                continue;
                            }

                            if (itemDataBean.getValue().length() > 3999) {
                                dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), formDataBean.getFormOID(), itemGroupDataBean.getItemGroupOID(), itemGroupDataBean.getItemGroupRepeatKey(), itemDataBean.getItemOID(), FAILED, "errorCode.valueTooLong ");
                                dataImportReports.add(dataImportReport);
                                logger.error("Item {} value too long over 3999", itemDataBean.getItemOID());
                                continue;
                            }

                            if (StringUtils.isNotEmpty(itemDataBean.getValue())) {
                                ErrorObj itemDataTypeErrorObj = validateItemDataType(item, itemDataBean.getValue());
                                if (itemDataTypeErrorObj != null) {
                                    dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), formDataBean.getFormOID(), itemGroupDataBean.getItemGroupOID(), itemGroupDataBean.getItemGroupRepeatKey(), itemDataBean.getItemOID(), FAILED, itemDataTypeErrorObj.getMessage());
                                    dataImportReports.add(dataImportReport);
                                    logger.error("Item {} data type error. {}", itemDataBean.getItemOID(), itemDataTypeErrorObj.getMessage());
                                    continue;
                                }

                                Set<ItemFormMetadata> ifms = item.getItemFormMetadatas();
                                ResponseSet responseSet = ifms.iterator().next().getResponseSet();
                                ErrorObj responseSetErrorObj = validateResponseSets(responseSet, itemDataBean.getValue());
                                if (responseSetErrorObj != null) {
                                    dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), formDataBean.getFormOID(), itemGroupDataBean.getItemGroupOID(), itemGroupDataBean.getItemGroupRepeatKey(), itemDataBean.getItemOID(), FAILED, responseSetErrorObj.getMessage());
                                    dataImportReports.add(dataImportReport);
                                    logger.error("Item {} response option text error. {}", itemDataBean.getItemOID(), responseSetErrorObj.getMessage());
                                    continue;
                                }
                            }

                            ItemData itemData = itemDataDao.findByItemEventCrfOrdinal(item.getItemId(), eventCrf.getEventCrfId(), Integer.parseInt(itemGroupDataBean.getItemGroupRepeatKey()));

                            if (itemData != null) {
                                if (itemData.getValue().equals(itemDataBean.getValue())) {
                                    dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), formDataBean.getFormOID(), itemGroupDataBean.getItemGroupOID(), itemGroupDataBean.getItemGroupRepeatKey(), itemDataBean.getItemOID(), SKIPPED, "");
                                    dataImportReports.add(dataImportReport);
                                    itemInsertedUpdatedSkippedCountInFrom++;
                                    logger.debug("Item {} value skipped ", itemDataBean.getItemOID());
                                } else {
                                    itemData = updateItemData(itemData, userAccount, itemDataBean.getValue());
                                    itemData = itemDataDao.saveOrUpdate(itemData);
                                    dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), formDataBean.getFormOID(), itemGroupDataBean.getItemGroupOID(), itemGroupDataBean.getItemGroupRepeatKey(), itemDataBean.getItemOID(), UPDATED, "");
                                    dataImportReports.add(dataImportReport);
                                    itemInsertedUpdatedCountInFrom++;
                                    itemInsertedUpdatedSkippedCountInFrom++;
                                    logger.debug("Item {} value updated ", itemDataBean.getItemOID());
                                }
                            } else {
                                itemData = createItemData(eventCrf, itemDataBean, userAccount, item, Integer.parseInt(itemGroupDataBean.getItemGroupRepeatKey()));
                                itemData = itemDataDao.saveOrUpdate(itemData);
                                dataImportReport = new DataImportReport(subjectDataBean.getSubjectOID(), subjectDataBean.getStudySubjectID(), studyEventDataBean.getStudyEventOID(), studyEventDataBean.getStudyEventRepeatKey(), formDataBean.getFormOID(), itemGroupDataBean.getItemGroupOID(), itemGroupDataBean.getItemGroupRepeatKey(), itemDataBean.getItemOID(), INSERTED, "");
                                dataImportReports.add(dataImportReport);
                                itemInsertedUpdatedCountInFrom++;
                                itemInsertedUpdatedSkippedCountInFrom++;
                                logger.debug("Item {} value inserted ", itemDataBean.getItemOID());
                            }
                        }//itemDataBean for loop
                    } //itemGroupDataBean for loop

                    if ((formDataBean.getEventCRFStatus().equals(COMPLETE) || formDataBean.getEventCRFStatus().equals(DATA_ENTRY_COMPLETE)) && itemInsertedUpdatedSkippedCountInFrom == itemCountInFormData) {                         // update eventcrf status into Complete
                        // Update Event Crf Status into Complete
                        eventCrf = updateEventCrf(eventCrf, userAccount, Status.UNAVAILABLE);
                        // check if all Forms within this Event is Complete
                        openRosaSubmissionController.updateStudyEventStatus(tenantStudy.getStudy() != null ? tenantStudy.getStudy() : tenantStudy, studySubject, studyEventDefinition, studyEvent, userAccount);
                        logger.debug("Form {} status updated to Complete ", formDataBean.getFormOID());

                    } else if (itemInsertedUpdatedCountInFrom > 0) {                         // update eventcrf status into data entry status
                        // Update Event Crf Status into Initial Data Entry
                        eventCrf = updateEventCrf(eventCrf, userAccount, Status.AVAILABLE);
                    }


                } // formDataBean for loop
            } // StudyEventDataBean for loop
        } // StudySubjectDataBean for loop

        writeToFile(dataImportReports, studyOid, fileName);
        userService.persistJobCompleted(jobDetail, fileName);

    }

    private void writeToFile(List<DataImportReport> dataImportReports, String studyOid, String fileName) {
        logger.debug("writing report to File");

        String filePath = getFilePath(JobType.XML_IMPORT) + File.separator + fileName;

        File file = new File(filePath);

        PrintWriter writer = null;
        try {
            writer = openFile(file);
        } catch (FileNotFoundException | UnsupportedEncodingException e) {
            e.printStackTrace();
        } finally {
            writer.print(writeToTextFile(dataImportReports));
            closeFile(writer);
        }
        StringBuilder body = new StringBuilder();


        logger.info(body.toString());


    }


    public String getFilePath(JobType jobType) {
        String dirPath = CoreResources.getField("filePath") + BULK_JOBS + File.separator + jobType.toString().toLowerCase();
        File directory = new File(dirPath);
        if (!directory.exists()) {
            directory.mkdirs();
        }
        return dirPath;
    }

    private PrintWriter openFile(File file) throws FileNotFoundException, UnsupportedEncodingException {
        PrintWriter writer = new PrintWriter(file.getPath(), "UTF-8");
        return writer;
    }


    private void closeFile(PrintWriter writer) {
        writer.close();
    }


    private String writeToTextFile(List<DataImportReport> dataImportReports) {

        StringBuffer stringBuffer = new StringBuffer();
        stringBuffer.append("SubjectKey");
        stringBuffer.append(SEPERATOR);
        stringBuffer.append("ParticipantID");
        stringBuffer.append(SEPERATOR);
        stringBuffer.append("StudyEventOID");
        stringBuffer.append(SEPERATOR);
        stringBuffer.append("StudyEventRepeatKey");
        stringBuffer.append(SEPERATOR);
        stringBuffer.append("FormOID");
        stringBuffer.append(SEPERATOR);
        stringBuffer.append("ItemGroupOID");
        stringBuffer.append(SEPERATOR);
        stringBuffer.append("ItemGroupRepeatKey");
        stringBuffer.append(SEPERATOR);
        stringBuffer.append("ItemOID");
        stringBuffer.append(SEPERATOR);
        stringBuffer.append("Status");
        stringBuffer.append(SEPERATOR);
        stringBuffer.append("Message");
        stringBuffer.append('\n');
        for (DataImportReport dataImportReport : dataImportReports) {
            stringBuffer.append(dataImportReport.getSubjectKey() != null ? dataImportReport.getSubjectKey() : "");
            stringBuffer.append(SEPERATOR);
            stringBuffer.append(dataImportReport.getStudySubjectID() != null ? dataImportReport.getStudySubjectID() : "");
            stringBuffer.append(SEPERATOR);
            stringBuffer.append(dataImportReport.getStudyEventOID() != null ? dataImportReport.getStudyEventOID() : "");
            stringBuffer.append(SEPERATOR);
            stringBuffer.append(dataImportReport.getStudyEventRepeatKey() != null ? dataImportReport.getStudyEventRepeatKey() : "");
            stringBuffer.append(SEPERATOR);
            stringBuffer.append(dataImportReport.getFormOID() != null ? dataImportReport.getFormOID() : "");
            stringBuffer.append(SEPERATOR);
            stringBuffer.append(dataImportReport.getItemGroupOID() != null ? dataImportReport.getItemGroupOID() : "");
            stringBuffer.append(SEPERATOR);
            stringBuffer.append(dataImportReport.getItemGroupRepeatKey() != null ? dataImportReport.getItemGroupRepeatKey() : "");
            stringBuffer.append(SEPERATOR);
            stringBuffer.append(dataImportReport.getItemOID() != null ? dataImportReport.getItemOID() : "");
            stringBuffer.append(SEPERATOR);
            stringBuffer.append(dataImportReport.getStatus() != null ? dataImportReport.getStatus() : "");
            stringBuffer.append(SEPERATOR);
            stringBuffer.append(dataImportReport.getMessage() != null ? dataImportReport.getMessage() : "");
            stringBuffer.append('\n');
        }

        StringBuilder sb = new StringBuilder();
        sb.append(stringBuffer.toString() + "\n");

        return sb.toString();
    }

    private ItemData createItemData(EventCrf eventCrf, ImportItemDataBean itemDataBean, UserAccount userAccount, Item item, int groupRepeatKey) {
        ItemData itemData = new ItemData();
        itemData.setEventCrf(eventCrf);
        itemData.setItem(item);
        itemData.setDeleted(false);
        itemData.setValue(itemDataBean.getValue());
        itemData.setUserAccount(userAccount);
        itemData.setDateCreated(new Date());
        itemData.setStatus(Status.AVAILABLE);
        itemData.setOrdinal(groupRepeatKey);
        logger.debug("Creating new Item Data");
        return itemData;
    }

    private ItemData updateItemData(ItemData itemData, UserAccount userAccount, String value) {
        itemData.setValue(value);
        itemData.setOldStatus(itemData.getStatus());
        itemData.setDateUpdated(new Date());
        itemData.setUpdateId(userAccount.getUserId());
        logger.debug("Updating Item Data Id {}", itemData.getItemDataId());
        return itemData;
    }


    private EventCrf createEventCrf(StudySubject studySubject, StudyEvent studyEvent, FormLayout formLayout, UserAccount userAccount) {
        EventCrf eventCrf = new EventCrf();
        CrfVersion crfVersion = crfVersionDao.findAllByCrfId(formLayout.getCrf().getCrfId()).get(0);
        Date currentDate = new Date();
        eventCrf.setAnnotations("");
        eventCrf.setDateCreated(currentDate);
        eventCrf.setCrfVersion(crfVersion);
        eventCrf.setFormLayout(formLayout);
        eventCrf.setInterviewerName("");
        eventCrf.setDateInterviewed(null);
        eventCrf.setUserAccount(userAccount);
        eventCrf.setStatusId(Status.AVAILABLE.getCode());
        eventCrf.setCompletionStatus(completionStatusDao.findByCompletionStatusId(1));// setCompletionStatusId(1);
        eventCrf.setStudySubject(studySubject);
        eventCrf.setStudyEvent(studyEvent);
        eventCrf.setValidateString("");
        eventCrf.setValidatorAnnotations("");
        eventCrf.setDateUpdated(new Date());
        eventCrf.setValidatorId(0);
        eventCrf.setOldStatusId(0);
        eventCrf.setSdvUpdateId(0);
        logger.debug("Creating new Event Crf");

        return eventCrf;
    }

    private EventCrf updateEventCrf(EventCrf eventCrf, UserAccount userAccount, Status formStatus) {
        eventCrf.setDateUpdated(new Date());
        eventCrf.setUpdateId(userAccount.getUserId());
        eventCrf.setOldStatusId(eventCrf.getStatusId());
        eventCrf.setStatusId(formStatus.getCode());
        logger.debug("Updating Event Crf Id {}", eventCrf.getEventCrfId());
        return eventCrf;
    }


    private StudyEvent createStudyEvent(StudySubject studySubject, StudyEventDefinition studyEventDefinition, int ordinal,
                                        UserAccount userAccount, String startDate, String endDate) {

        StudyEvent studyEvent = new StudyEvent();
        studyEvent.setStudyEventDefinition(studyEventDefinition);
        studyEvent.setSampleOrdinal(ordinal);
        studyEvent.setSubjectEventStatusId(SubjectEventStatus.SCHEDULED.getCode());
        studyEvent.setStatusId(Status.AVAILABLE.getCode());
        studyEvent.setStudySubject(studySubject);
        studyEvent.setDateCreated(new Date());
        studyEvent.setUserAccount(userAccount);
        studyEvent.setDateStart(null);
        studyEvent.setDateEnd(null);

        if (startDate != null) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH);
            LocalDate localDate = LocalDate.parse(startDate, formatter);
            Date date = java.sql.Date.valueOf(localDate);
            studyEvent.setDateStart(date);
        }
        if (endDate != null) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH);
            LocalDate localDate = LocalDate.parse(endDate, formatter);
            Date date = java.sql.Date.valueOf(localDate);
            studyEvent.setDateEnd(date);
        }
        studyEvent.setStartTimeFlag(false);
        studyEvent.setEndTimeFlag(false);
        logger.debug("Creating new Study Event");
        return studyEvent;
    }

    private StudyEvent updateStudyEvent(StudyEvent studyEvent, UserAccount userAccount) {
        studyEvent.setDateUpdated(new Date());
        studyEvent.setUpdateId(userAccount.getUserId());
        studyEvent.setSubjectEventStatusId(SubjectEventStatus.DATA_ENTRY_STARTED.getCode());
        logger.debug("Updating Study Event Id {}", studyEvent.getStudyEventId());
        return studyEvent;
    }

    private ErrorObj createErrorObj(String code, String message) {
        ErrorObj errorObj = new ErrorObj();
        errorObj.setCode(code);
        errorObj.setMessage(message);
        return errorObj;
    }

    private ErrorObj validateItemDataType(Item item, String value) {
        ItemDataType itemDataType = item.getItemDataType();
        switch (itemDataType.getCode()) {
            case "BL":
                return validateForBoolean(value);
            case "ST":
                return null;
            case "INT":
                return validateForInteger(value);
            case "REAL":
                return validateForReal(value);
            case "DATE":
                return validateForDate(value);
            case "PDATE":
                return new ErrorObj(FAILED, "errorCode.itemTypeNotSupportedInImport");
            case "FILE":
                return new ErrorObj(FAILED, "errorCode.itemTypeNotSupportedInImport");
            default:
                return new ErrorObj(FAILED, "errorCode.itemTypeNotSupportedInImport");
        }

    }


    private ErrorObj validateForBoolean(String value) {
        if (!value.equals("true") && !value.equals("false")) {
            return new ErrorObj(FAILED, "errorCode.valueTypeMismatch");
        }
        return null;

    }

    private ErrorObj validateForInteger(String value) {
        try {
            Integer int1 = Integer.parseInt(value);
            return null;
        } catch (NumberFormatException nfe) {
            nfe.getStackTrace();
            return new ErrorObj(FAILED, "errorCode.valueTypeMismatch");
        }
    }

    private ErrorObj validateForReal(String value) {
        if (isNumeric(value))
            return null;
        else
            return new ErrorObj(FAILED, "errorCode.valueTypeMismatch");
    }

    private boolean isNumeric(String str) {
        return str.matches("^\\d+(\\.\\d+)?");
    }

    private ErrorObj validateForDate(String value) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH);
            LocalDate date = LocalDate.parse(value, formatter);
            return null;
        } catch (Exception pe) {
            pe.getStackTrace();
            return new ErrorObj(FAILED, "errorCode.invalidDateFormat");
        }
    }


    private ErrorObj validateResponseSets(ResponseSet responseSet, String value) {
        ResponseType responseType = responseSet.getResponseType();
        switch (responseType.getName()) {
            case ("checkbox"):
                return validateCheckBoxOrMultiSelect(responseSet, value);
            case ("multi-select"):
                return validateCheckBoxOrMultiSelect(responseSet, value);
            case ("radio"):
                return validateRadioOrSingleSelect(responseSet, value);
            case ("single-select"):
                return validateRadioOrSingleSelect(responseSet, value);
            case ("text"):
                return null;
            case ("textarea"):
                return null;
            case ("file"):
                return new ErrorObj(FAILED, "errorCode.itemTypeNotSupportedInImport");
            case ("calculation"):
                return new ErrorObj(FAILED, "errorCode.itemTypeNotSupportedInImport");
            default:
                return new ErrorObj(FAILED, "errorCode.itemTypeNotSupportedInImport");

        }
    }


    private ErrorObj validateRadioOrSingleSelect(ResponseSet responseSet, String value) {
        if (!responseSet.getOptionsValues().contains(value)) {
            return new ErrorObj(FAILED, "errorCode.valueChoiceCodeNotFound");
        }
        return null;
    }

    private ErrorObj validateCheckBoxOrMultiSelect(ResponseSet responseSet, String value) {
        String[] values = value.split(",");
        ArrayList list = new ArrayList(Arrays.asList(values));

        for (String v : values) {
            if (!responseSet.getOptionsValues().contains(v)) {
                return new ErrorObj(FAILED, "errorCode.valueChoiceCodeNotFound");
            }
        }
        return null;
    }


}