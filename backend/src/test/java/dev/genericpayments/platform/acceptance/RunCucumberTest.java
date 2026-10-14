package dev.genericpayments.platform.acceptance;
import static io.cucumber.junit.platform.engine.Constants.*;
import org.junit.platform.suite.api.*;
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.genericpayments.platform.acceptance")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty,summary")
/** Includes unchanged Stage 1/2 features and the real PostgreSQL/Mailpit Customer feature. */
public class RunCucumberTest {}
