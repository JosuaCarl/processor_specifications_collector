package org.cpuinfofetcher

import java.nio.file.Path
import java.nio.file.Paths

import java.time.temporal.ChronoUnit

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Callable
import java.util.concurrent.Future

import org.jsoup.nodes.Element
import org.jsoup.select.Elements

import me.tongfei.progressbar.ProgressBar

import org.dflib.DataFrame
import org.dflib.JoinType
import org.dflib.Printers
import org.dflib.csv.Csv

/**
 * Fetch Intel specifications of processors
 * @author Josua Carl
 * @version 1.0
 * @since 1.0
 */
class IntelSpecificationsFetcher extends SpecificationsFetcher {

    int numThreads = 1
    IntelSpecificationsFetcher(int numThreads) {
        this.numThreads = numThreads
    }
    // Scraper
    HTMLScraper scraper = new HTMLScraper()

    // Execution instances
    ProgressBar progressBar
    ExecutorService threadPool = Executors.newFixedThreadPool(numThreads)

    // Mapping possible naming schenes for attributes
    Map<String, String[]> specification_aliases = [
        'tgb': ['Processor Base Power', 'tdp', 'thermal design power', 'Scenario Design Power', 'SDP'],
        'cores': ['cores'],
        'threads': ['threads']
    ]

    List<String> standard_cols = ['product_id', 'name', 'time', 'source']


    // Extract processor family urls from main ark
    protected DataFrame fetch_processor_family_urls(String url, Path snap_path) {
        snap_path = snap_path.resolve('Intel_family_info.csv')

        // Get snapshot & Update time
        def df = check_snap(snap_path, [*this.standard_cols, 'url'])
        int days_since_update = check_last_update(df, ChronoUnit.DAYS)

        if (days_since_update > 28 || days_since_update < 0) {
            // divs with class "products processors" -> "a" elements with hrefs containing "processor"
            String xPath_query = './/div[@class=\'products processors\']//a[contains(@href, "processor")]'
            Elements elements = this.scraper.scrape(url, xPath_query)

            // Make data matrix
            for (Element element : elements) {
                df.append(
                    element.ownText(),
                    element.ownText(),
                    timeFormat.format(this.localTime.now()),
                    url,
                    element.attr('abs:href'),
                )
            }
            df = df.toDataFrame()

            // Save info
            Csv.save(df, snap_path)
        }

        this.progressBar.step()
        return df
    }


    // Extract processor urls from main ark
    protected DataFrame fetch_processor_urls(DataFrame family_url, Path snap_path) {
        String url = family_url.get('url', 0)
        String name = family_url.get('name', 0)
        name = name.replaceAll('[^a-zA-Z0-9 ]+', '').replace(' ', '_')
        snap_path = snap_path.resolve(name + '.csv')

        // Get snapshot & Update time
        def df = check_snap(snap_path, [*this.standard_cols, 'url'])
        int days_since_update = check_last_update(df, ChronoUnit.DAYS)

        if (days_since_update > 28 || days_since_update < 0) {
            // table with id "product-table" -> table body -> table rows
            String xPath_query = './/table[@id=\'product-table\']//tbody/tr'
            Elements elements = this.scraper.scrape(url, xPath_query)

            // Make data matrix
            for (Element element : elements) {
                String product_id = element.attr('abs:data-product-id')
                // table data with data-component "arkproductlink" -> "a" elements
                xPath_query = './/td[@data-component=\'arkproductlink\']//a'
                Element link_element = element.selectXpath(xPath_query).first()
                if (link_element) {
                    df.append(
                        product_id,
                        link_element.ownText(),
                        timeFormat.format(this.localTime.now()),
                        link_element.attr('abs:href'),
                        url
                    )
                }
            }
            df = df.toDataFrame()

            // Save info
            Csv.save(df, snap_path)
        }

        this.progressBar.step()
        return df
    }


    // Single run specification fetching
    protected DataFrame fetch_processor_specification(DataFrame processor_url, Path snap_path) {
        String name = processor_url.get('name', 0)
        name = name.replaceAll('[^a-zA-Z0-9 ]+', '').replace(' ', '_')
        snap_path = snap_path.resolve(name + '.csv')

        // Get snapshot & Update time
        def df = check_snap(snap_path, [])
        int days_since_update = check_last_update(df, ChronoUnit.DAYS)

        if (days_since_update > 28 || days_since_update < 0) {
            String url = processor_url.get('url', 0)
            // divs with class "products processors" -> "a" elements with hrefs containing "processor" -> hrefs
            String xPath_query = './/div[contains(@id, "spec")]//div[contains(@class, "tech-section")]'
            Elements elements = this.scraper.scrape(url, xPath_query)

            // Extract data
            List<String> labels = []
            List<String> data = []
            for (Element element : elements) {
                xPath_query = './/div[contains(@class, "tech-label")]'
                Element label_element = element.selectXpath(xPath_query).first()
                xPath_query = './/div[contains(@class, "tech-data")]'
                Element data_element = element.selectXpath(xPath_query).first()
                if (label_element != null && data_element != null){
                    if (!labels.contains(label_element.text())) {
                        labels.add(label_element.text())
                        data.add(data_element.text())
                    }
                }
            }

            // Merge info into url row
            df = DataFrame
                .foldByColumn(*this.standard_cols, *labels)
                .of(
                    processor_url.get('product_id', 0),
                    processor_url.get('name', 0),
                    timeFormat.format(this.localTime.now()),
                    url,
                    *data
                )

            // Save info
            Csv.save(df, snap_path)
        }

        this.progressBar.step()

        return df
    }

    // Single run Callable
    protected class FetchSpecification implements Callable<DataFrame> {

        DataFrame processor_url
        Path snap_path

        FetchSpecification(DataFrame processor_url, Path snap_path) {
            this.processor_url = processor_url
            this.snap_path = snap_path
        }

        @Override
        DataFrame call() { return fetch_processor_specification(this.processor_url, this.snap_path) }

    }


    // Multible run specification fetching
    protected DataFrame fetch_specifications(DataFrame processor_urls, Path snap_path) {
        // Get calls
        List<FetchSpecification> callables = []
        for (int i = 0; i < processor_urls.height(); i++) {
            callables.add(
                new FetchSpecification(
                    processor_urls.rows(i).select(),
                    snap_path.resolve('processor_infos')
                )
            )
        }

        // Invoke calls
        List<Future> futures
        try {
            futures = this.threadPool.invokeAll(callables)
        } catch (InterruptedException e) {
            e.printStackTrace()
            this.threadPool.shutdownNow()
        }
        this.threadPool.shutdown()

        // Extract and save results
        snap_path = snap_path.resolve('Intel_processor_specifications.csv')
        DataFrame specifications = DataFrame.empty()
        for (Future future : futures) {
            // Extract df from future
            def df = future.get()

            // Find columns with desired info
            Map<String, String> matched_cols = [:]
            specification_aliases.each { specification_key, aliases ->
                matched_cols.put(
                    specification_key,
                    df.getColumnsIndex().toArray().find { col_name ->
                        aliases.any { alias -> col_name.toLowerCase().contains(alias.toLowerCase()) }
                    }
                )
            }
            // If not thread information is provided (older models), use core information
            if (matched_cols.get('threads') == null) {
                matched_cols.put('threads', matched_cols.get('cores'))
            }
            if (!matched_cols.values().contains(null)) {
                // Add specification to collection dataframe
                specifications = specifications.vConcat(
                    JoinType.full,
                    df.cols(*this.standard_cols, *matched_cols.values().toArray())
                        .selectAs(*this.standard_cols, *matched_cols.keySet().toArray())
                )
            }
        }

        Csv.save(specifications, snap_path)

        return specifications
    }


    // Main (invoker)
    DataFrame main() {
        // Add path for assets
        String script_path = getClass().protectionDomain.codeSource.location.path
        Path snap_path = Paths.get(script_path, '..', '..', '..', 'resources', 'main', 'assets')
            .toAbsolutePath()
            .normalize()

        // Intel
        snap_path = snap_path.resolve('Intel')

        // Collect Processor family URLs
        this.progressBar = new ProgressBar('Fetching processor family URLs:', 1)
        DataFrame family_urls = fetch_processor_family_urls(
            'https://www.intel.com/content/www/us/en/ark.html',
            snap_path.resolve('processor_family_urls'),
        )

        // Collect processor URLs
        this.progressBar = new ProgressBar('Fetching processor URLs:', family_urls.height())
        DataFrame processor_urls = DataFrame
            .byArrayRow(*this.standard_cols, 'url')
            .appender()
            .toDataFrame()
        for (int i = 0; i < family_urls.height(); i++) {
            DataFrame family_url = family_urls.rows(i).select()
            processor_urls = processor_urls.vConcat(
                fetch_processor_urls(
                    family_url,
                    snap_path.resolve('processor_urls'),
                )
            )
        }

        // Collect processor specifications
        this.progressBar = new ProgressBar('Fetching Processor Info:', processor_urls.size())
        DataFrame specifications = fetch_specifications(
            processor_urls,
            snap_path,
        )

        return specifications
    }

}
