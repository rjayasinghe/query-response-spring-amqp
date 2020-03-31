package app;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import com.studiomediatech.queryresponse.EnableQueryResponse;
import com.studiomediatech.queryresponse.QueryBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.util.List;


@SpringBootApplication
@EnableQueryResponse
class Querying implements CommandLineRunner {

    private static final Logger LOG = LoggerFactory.getLogger(Querying.class);

    @Override
    public void run(String... args) throws Exception {

        LOG.info("About to begin querying...");
        Thread.sleep(3000);

        while (true) {
            queryForBooks();
            queryForAuthors();
        }
    }


    private void queryForBooks() throws InterruptedException {

        LOG.info("Publishing query..");

        var defaults = List.of("Neuromancer", "I, Robot");

        var results = QueryBuilder.queryFor("books/sci-fi", String.class)
                .waitingFor(2000)
                .orDefaults(defaults);

        LOG.info("Results were: {} {}", results, results.equals(defaults) ? "(defaults)" : "");

        pause();
    }


    private void queryForAuthors() throws InterruptedException {

        LOG.info("Querying for authors...");

        var authors = QueryBuilder.queryFor("authors", Author.class)
                .takingAtLeast(3)
                .waitingFor(888)
                .orEmpty();

        LOG.info("Results were: {} {}", authors, authors.isEmpty() ? "(not enough)" : "");

        pause();
    }


    private void pause() throws InterruptedException {

        LOG.info("Sleeping for 10s...");
        Thread.sleep(10000);
    }


    public static void main(String[] args) {

        new SpringApplicationBuilder(Querying.class).web(WebApplicationType.NONE).run(args);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Author {

        public String name;
        public String country;

        public String getName() {

            return name;
        }


        public void setName(String name) {

            this.name = name;
        }


        public String getCountry() {

            return country;
        }


        public void setCountry(String country) {

            this.country = country;
        }


        @Override
        public String toString() {

            return "Author [name=" + name + ", country=" + country + "]";
        }
    }
}
