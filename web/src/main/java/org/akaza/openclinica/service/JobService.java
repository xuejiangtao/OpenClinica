/**
 *
 */
package org.akaza.openclinica.service;

import org.akaza.openclinica.bean.login.UserAccountBean;
import org.akaza.openclinica.controller.dto.JobDetailDTO;
import org.akaza.openclinica.domain.datamap.JobDetail;
import org.akaza.openclinica.domain.datamap.Study;

import java.util.List;

/**
 * @author joekeremian
 *
 */

public interface JobService {


    List<JobDetailDTO> findAllNonDeletedJobsBySite(Study tenantSite,UserAccountBean userAccountBean);

    List<JobDetailDTO> findAllNonDeletedJobsByStudy(Study tenantStudy,UserAccountBean userAccountBean);

    JobDetail saveOrUpdateJob(JobDetail jobDetail);

    void deleteJob(JobDetail jobDetail, UserAccountBean userAccountBean);

}