/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.reporter.file;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.TimeZone;

import org.apache.commons.lang3.time.FastDateFormat;
import org.eclipse.jetty.util.RolloverFileOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import io.gravitee.common.service.AbstractService;
import io.gravitee.reporter.api.Reportable;
import io.gravitee.reporter.api.Reporter;
import io.gravitee.reporter.api.http.RequestMetrics;
import io.gravitee.reporter.file.config.Config;

/**
 * Write an access log to a file by using the following line format:
 *
 * <pre>
 *     [TIMESTAMP] (LOCAL_IP) REMOTE_IP API KEY METHOD PATH STATUS LENGTH TOTAL_RESPONSE_TIME
 * </pre>
 * 
 * This class is not thread safe, the record method should only be called by a single thread
 *
 * @author David BRASSELY (brasseld at gmail.com)
 */
@SuppressWarnings("rawtypes")
public class FileReporter extends AbstractService implements Reporter {

	@Autowired
	private Config config;
	
	private static final Logger LOGGER = LoggerFactory.getLogger(FileReporter.class);

	private static final String RFC_3339_DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSSZ";

	private static final FastDateFormat dateFormatter = FastDateFormat.getInstance(RFC_3339_DATE_FORMAT);

	private static final String NO_STRING_DATA_VALUE = "-";

	private static final String NO_INTEGER_DATA_VALUE = "-1";

	// buffer reused between calls to the report method
	private final StringBuilder accessLogBuffer = new StringBuilder(256);
	private final StringBuffer dateFormatBuffer = new StringBuffer();
	private final char[] stringBuilderConverterBuffer = new char[2048];

	private transient OutputStream _out;

	private transient Writer _writer;

	private void write(StringBuilder accessLog) throws IOException {
		synchronized (this) {
			if (_writer == null) {
				return;
			}

			/*
			 * OMG What's going on ?
			 * Why aren't you doing a _writer.write(accessLog.toString()) or _writer.write(accessLog) ?
			 * Because it's doing too many memory allocations !
			 * accessLog.toString() will create a copy of the the StringBuilder content into a String
			 * then _writer.write will make another copy of the content of the String
			 * Here we're doing only one copy AND we reuse the buffer.
			 */
			int length = accessLog.length();
			int chunkLength = stringBuilderConverterBuffer.length;
			for (int srcBegin = 0; srcBegin < length; srcBegin += chunkLength) {
				final int srcEnd = Math.min(srcBegin + chunkLength, length);
				accessLog.getChars(srcBegin, srcEnd, stringBuilderConverterBuffer, 0);
				_writer.write(stringBuilderConverterBuffer, 0, srcEnd - srcBegin);
			}
			_writer.flush();
		}
	}

	private StringBuilder format(RequestMetrics metrics) {
		StringBuilder buf = accessLogBuffer;
		StringBuffer dateBuffer = dateFormatBuffer;
		buf.setLength(0);
		dateBuffer.setLength(0);

		// Append request timestamp
		buf.append('[');
		dateFormatter.format(metrics.timestamp().toEpochMilli(), dateBuffer);
		buf.append(dateBuffer);
		buf.append("] ");

		// Append local IP
		buf.append('(');
		buf.append(metrics.getRequestLocalAddress());
		buf.append(") ");

		// Append remote IP
		buf.append(metrics.getRequestRemoteAddress());
		buf.append(' ');

		// Append Api name
		String apiName = metrics.getApi();
		if (apiName == null) {
			apiName = NO_STRING_DATA_VALUE;
		}

		buf.append(apiName);
		buf.append(' ');

		// Append key
		String apiKey = metrics.getApiKey();
		if (apiKey == null) {
			apiKey = NO_STRING_DATA_VALUE;
		}
		buf.append(apiKey);
		buf.append(' ');

		// Append request method and URI
		buf.append(metrics.getRequestHttpMethod());
		buf.append(' ');
		buf.append(metrics.getRequestPath());
		buf.append(' ');

		// Append response status
		int status = metrics.getResponseHttpStatus();
		if (status <= 0) {
			status = 404;
		}
		buf.append((char) ('0' + ((status / 100) % 10)));
		buf.append((char) ('0' + ((status / 10) % 10)));
		buf.append((char) ('0' + (status % 10)));
		buf.append(' ');

		// Append response length
		long responseLength = metrics.getResponseContentLength();
		if (responseLength >= 0) {
			if (responseLength > 99999) {
				buf.append(responseLength);
			} else {
				if (responseLength > 9999)
					buf.append((char) ('0' + ((responseLength / 10000) % 10)));
				if (responseLength > 999)
					buf.append((char) ('0' + ((responseLength / 1000) % 10)));
				if (responseLength > 99)
					buf.append((char) ('0' + ((responseLength / 100) % 10)));
				if (responseLength > 9)
					buf.append((char) ('0' + ((responseLength / 10) % 10)));
				buf.append((char) ('0' + (responseLength) % 10));
			}
		} else {
			buf.append(NO_INTEGER_DATA_VALUE);
		}
		buf.append(' ');

		// Append total response time
		buf.append(metrics.getProxyResponseTimeMs());
		
		buf.append(System.lineSeparator());

		return buf;
	}

	@Override
	public synchronized void doStart() throws Exception {
        String filename = config.getFilename();
        if (filename != null) {
            _out = new RolloverFileOutputStream(
                    filename,
					config.isAppend(),
					config.getRetainDays(),
                    TimeZone.getDefault(),
					config.getDateFormat(),
					config.getBackupFormat()
            );
            LOGGER.info("Opened rollover access log file " + filename);
        }

		synchronized (this) {
			_writer = new OutputStreamWriter(_out);
		}
	}
	
	@Override
	public synchronized void doStop() throws Exception {
		synchronized (this) {
			try {
				if (_writer != null)
					_writer.flush();
			} catch (IOException ioe) {
				LOGGER.error("", ioe);
			}
			if (_out != null)
				try {
					_out.close();
				} catch (IOException ioe) {
					LOGGER.error("", ioe);
				}

			_out = null;
			_writer = null;
		}
	}

	@Override
	public void report(Reportable reportable) {
		try {
			write(format((RequestMetrics) reportable));
		} catch (IOException ioe) {
			LOGGER.error("", ioe);
		}
	}

	@Override
	public boolean canHandle(Reportable reportable) {
		return (reportable instanceof RequestMetrics);
	}
}
