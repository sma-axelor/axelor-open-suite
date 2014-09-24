/**
 * Axelor Business Solutions
 *
 * Copyright (C) 2014 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.apps.base.web;

import com.axelor.apps.base.db.IndicatorGenerator;
import com.axelor.apps.base.service.administration.IndicatorGeneratorService;
import com.axelor.exception.service.TraceBackService;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Inject;

public class IndicatorGeneratorController {

	@Inject
	private IndicatorGeneratorService indicatorGeneratorService;
	
	public void run(ActionRequest request, ActionResponse response){
		
		IndicatorGenerator indicatorGenerator = request.getContext().asType(IndicatorGenerator.class);
		
		try {
			indicatorGeneratorService.run(indicatorGeneratorService.find(indicatorGenerator.getId()));
			response.setReload(true);
			response.setFlash("Requête exécutée");
		}
		catch (Exception e) { TraceBackService.trace(response, e); }
	}
}
