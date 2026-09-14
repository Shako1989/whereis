package az.technest.whereis.common.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Clean, extension-less URLs for the two pages Google Play links to from the store listing.
 * The static resource handler serves {@code /legal/privacy.html} on its own but has no
 * welcome-file resolution below the context root, so {@code /legal/privacy} would be a 404
 * without these forwards. Both forms are covered by the {@code /legal/**} permitAll matcher.
 */
@Configuration
public class LegalPagesConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/legal/delete-account").setViewName("forward:/legal/delete-account.html");
        registry.addViewController("/legal/privacy").setViewName("forward:/legal/privacy.html");
    }
}
