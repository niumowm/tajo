/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tajo.engine.function.builtin;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import tajo.catalog.Column;
import tajo.catalog.function.GeneralFunction;
import tajo.catalog.proto.CatalogProtos.DataType;
import tajo.datum.Datum;
import tajo.datum.DatumFactory;
import tajo.datum.LongDatum;
import tajo.storage.Tuple;

import java.text.ParseException;
import java.text.SimpleDateFormat;

public class Date extends GeneralFunction<LongDatum> {
  private final Log LOG = LogFactory.getLog(Date.class);
  private final static String dateFormat = "dd/MM/yyyy HH:mm:ss";

  public Date() {
    super(new Column[] {new Column("val", DataType.STRING)});
  }

  @Override
  public Datum eval(Tuple params) {
    try {
      return DatumFactory.createLong(new SimpleDateFormat(dateFormat)
          .parse(params.get(0).asChars()).getTime());
    } catch (ParseException e) {
      LOG.error(e);
      return null;
    }
  }
}
