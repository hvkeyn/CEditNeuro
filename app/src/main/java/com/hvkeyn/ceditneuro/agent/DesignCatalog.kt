package com.hvkeyn.ceditneuro.agent

/**
 * Names from the public gallery at designsystems.one.
 * Swatches are the samples shown on each card, not a full token set.
 */
object DesignCatalog {
    data class System(
        val slug: String,
        val name: String,
        val vendor: String,
        val note: String,
        val swatches: List<String>,
    )

    val systems: List<System> = listOf(
        System("material-design", "Material Design", "Google", "A design system by Google that helps teams build high-quality digital experiences.", emptyList()),
        System("fluent-design", "Fluent Design System", "Microsoft", "Microsoft's design system for creating adaptive, coherent, and inclusive user experiences.", listOf("#0f6cbd", "#ffffff", "#242424")),
        System("carbon-design", "Carbon Design System", "IBM", "IBM's open-source design system for products and digital experiences.", listOf("#f4f4f4", "#161616", "#0f62fe", "#da1e28")),
        System("lightning-design", "Lightning Design System", "Salesforce", "A framework for creating unified enterprise experiences, design patterns, and accessible interfaces.", listOf("#f3f3f3", "#181818", "#1b96ff")),
        System("atlassian-design", "Atlassian Design System", "Atlassian", "A collection of UI patterns and components used across Atlassian products.", listOf("#091e420f", "#172b4d", "#0c66e4")),
        System("polaris", "Polaris", "Shopify", "A design system that helps Shopify build better experiences for merchants.", listOf("#ffffff", "#202223", "#d72c0d")),
        System("primer", "Primer", "GitHub", "GitHub's design system for creating cohesive, accessible, and responsive interfaces.", listOf("#1f2328", "#ffffff", "#0969da", "#d1242f")),
        System("garden", "Garden", "Zendesk", "Zendesk's design system for creating consistent, accessible customer experiences.", emptyList()),
        System("spectrum", "Spectrum", "Adobe", "The design system behind Creative Cloud, built on React Aria — Adobe's accessibility primitives, now used well beyond Adobe.", emptyList()),
        System("ant-design", "Ant Design", "Ant Group", "A design system for enterprise-level products with a set of high-quality React components.", emptyList()),
        System("chakra-ui", "Chakra UI", "Chakra UI", "A simple, modular and accessible component library for React applications.", emptyList()),
        System("tailwind-ui", "Tailwind Plus", "Tailwind Labs", "Professional, hand-crafted UI components and templates built with Tailwind CSS (formerly Tailwind UI).", listOf("#4f46e5", "#111827", "#6b7280")),
        System("bootstrap", "Bootstrap", "Bootstrap Team", "The world's most popular framework for building responsive, mobile-first sites.", emptyList()),
        System("bulma", "Bulma", "Bulma Team", "A free, open source CSS framework based on Flexbox.", emptyList()),
        System("foundation", "Foundation", "ZURB", "The most advanced responsive front-end framework in the world.", emptyList()),
        System("semantic-ui", "Semantic UI", "Semantic UI", "A development framework that helps create beautiful, responsive layouts.", emptyList()),
        System("evergreen", "Evergreen", "Segment", "A React UI Framework for building ambitious products on the web.", emptyList()),
        System("gestalt", "Gestalt", "Pinterest", "A set of React UI components that supports Pinterest's design language.", listOf("#111111", "#ffffff", "#ad081b")),
        System("baseweb", "Base Web", "Uber", "A React Component library implementing the Base design language.", emptyList()),
        System("ring-ui", "Ring UI", "JetBrains", "A collection of UI components for web applications.", emptyList()),
        System("paste", "Paste", "Twilio", "A design system for building consistent, accessible UIs at Twilio.", listOf("#ffffff", "#121c2d", "#0263e0")),
        System("radix-ui", "Radix UI", "Workos", "Unstyled, accessible components for building high‑quality design systems.", emptyList()),
        System("mantine", "Mantine", "Mantine", "A fully featured React components library with 100+ customizable components.", emptyList()),
        System("nextui", "HeroUI", "HeroUI", "Beautiful, fast and modern React UI library (formerly NextUI).", emptyList()),
        System("mui", "Material UI", "MUI", "React components for faster and easier web development based on Material Design.", emptyList()),
        System("shadcn-ui", "shadcn/ui", "shadcn", "Beautifully designed components built with Radix UI and Tailwind CSS.", emptyList()),
        System("radianui", "RadianUI", "Radian", "Open-source component library built with Radix UI and Tailwind CSS, with a free Figma UI kit and a growing set of UI blocks.", emptyList()),
        System("boa-design", "Bank of Canada Design System", "Bank of Canada", "The Bank of Canada's design system for the public-facing sites of Canada's central bank.", emptyList()),
        System("amazon-design", "Cloudscape Design System", "Amazon Web Services", "An open source design system for the cloud, built by Amazon Web Services (AWS) to create web applications.", listOf("#fafafa", "#414d5c", "#0972d3")),
        System("ebay-design", "eBay Design System", "eBay", "eBay Evo is a design system that connects people with passions, and enables eBay to build great experiences for customers.", listOf("#e53238", "#0064d2", "#111820", "#707070")),
        System("bbc-gel", "BBC GEL (Global Experience Language)", "BBC", "Design system for BBC's digital products and services.", listOf("#141414", "#bb1919", "#5a5a5a")),
        System("usds-design", "U.S. Web Design System", "U.S. Government", "Design system for the U.S. federal government's digital services.", listOf("#005ea2", "#1b1b1b", "#ffffff")),
        System("gov-uk-design", "GOV.UK Design System", "UK Government", "Design system for UK government services and websites.", listOf("#1d70b8", "#0b0c0c", "#d4351c", "#ffdd00")),
        System("canada-design", "Canada.ca Design System", "Government of Canada", "Design system for Government of Canada websites and digital services.", emptyList()),
        System("australia-design", "Australian Government Design System", "Australian Government", "Design system for Australian Government websites and digital services.", emptyList()),
        System("singapore-design", "Singapore Government Design System", "Singapore Government", "Design system for Singapore Government digital services.", emptyList()),
        System("airbnb-design", "Airbnb Design System", "Airbnb", "Design system for Airbnb's accommodation booking platform.", listOf("#ff5a5f", "#222222", "#717171", "#ffffff")),
        System("stripe-design", "Stripe Design System", "Stripe", "Design system for Stripe's payment processing platforms.", listOf("#635bff", "#1a1a1a", "#f6f9fc", "#df1b41")),
        System("square-design", "Square Design System", "Square", "Design system for Square's payment and business solutions.", listOf("#000000", "#ffffff", "#008060", "#cc4b00")),
        System("visa-design", "Visa Design System", "Visa", "Design system for Visa's payment services.", emptyList()),
        System("washpost-design", "Washington Post Design System", "The Washington Post", "Design system for The Washington Post's digital news platforms.", emptyList()),
        System("eu-design", "European Commission Design System", "European Commission", "Design system for European Commission digital services.", emptyList()),
        System("yale-design", "Yale Design System", "Yale University", "Design system for Yale University's digital platforms.", emptyList()),
        System("berkeley-design", "UC Berkeley Design System", "UC Berkeley", "Design system for UC Berkeley's digital platforms.", emptyList()),
        System("columbia-design", "Columbia Design System", "Columbia University", "Design system for Columbia University's digital platforms.", emptyList()),
        System("dropbox-design", "Dropbox Design System", "Dropbox", "Design system for Dropbox's file storage and sharing platform.", listOf("#0061ff", "#1e1919", "#ffffff", "#2ec866")),
        System("asana-design", "Asana Design System", "Asana", "Design system for Asana's project management platform.", emptyList()),
        System("hsbc-design", "HSBC Design System", "HSBC", "Design system for HSBC's banking platforms.", emptyList()),
        System("johns-hopkins-design", "Johns Hopkins Design System", "Johns Hopkins Medicine", "Design system for Johns Hopkins' healthcare platforms.", emptyList()),
        System("nhs-design", "NHS Design System", "National Health Service", "Design system for the UK's National Health Service digital platforms.", listOf("#005eb8", "#212b32", "#ffffff", "#d5281b")),
        System("alibaba-design", "Alibaba Design System", "Alibaba", "Design system for Alibaba's e-commerce platforms.", emptyList()),
        System("rakuten-design", "Rakuten Design System", "Rakuten", "Design system for Rakuten's e-commerce platforms.", emptyList()),
        System("apple-hig", "Apple Human Interface Guidelines", "Apple", "Comprehensive design system for iOS, macOS, watchOS, and tvOS applications with guidelines for creating intuitive experiences.", emptyList()),
        System("vuetify", "Vuetify", "Vuetify Team", "Material Design Component Framework for Vue.js with over 100 components.", emptyList()),
        System("blueprint", "Blueprint", "Palantir", "React-based UI toolkit for the web, optimized for building complex data-dense interfaces.", emptyList()),
        System("reakit", "Ariakit", "Ariakit", "Toolkit for building accessible rich web applications with React (formerly Reakit).", emptyList()),
        System("grommet", "Grommet", "Hewlett Packard Enterprise", "React-based framework providing accessibility, modularity, responsiveness, and theming.", emptyList()),
        System("elastic-ui", "Elastic UI", "Elastic", "Framework for creating accessible, composable, and beautiful user interfaces.", emptyList()),
        System("workday-canvas", "Canvas Design System", "Workday", "Design system for building consistent, accessible, and high-quality experiences at Workday.", listOf("#0875e1", "#191e23", "#ffffff", "#de2e21")),
        System("rei-cedar", "Cedar Design System", "REI", "Design system for REI's outdoor retail experiences, focusing on accessibility and sustainability.", emptyList()),
        System("audi-ui", "Audi UI", "Audi", "Design system for Audi's digital automotive experiences and connected car interfaces.", listOf("#bb0a30", "#000000", "#ffffff")),
        System("mailchimp-design", "Mailchimp Design System", "Mailchimp", "Design system for Mailchimp's email marketing and automation platform.", listOf("#ffe01b", "#241c15", "#007c89", "#00a896")),
        System("monday-vibe", "Vibe Design System", "monday.com", "monday.com's design system for work management platform with accessible components.", emptyList()),
        System("duolingo-design", "Duolingo Design System", "Duolingo", "Design system for Duolingo's language learning platform with playful, engaging interfaces.", emptyList()),
        System("gitlab-pajamas", "Pajamas", "GitLab", "GitLab's open-source design system that powers GitLab.com and the GitLab platform.", listOf("#303030", "#737278", "#ec5941", "#1f75cb")),
        System("skyscanner-backpack", "Backpack", "Skyscanner", "Skyscanner's open-source design system for native iOS, Android, and the web.", listOf("#0062e3", "#00a698", "#111236", "#ffffff")),
        System("hashicorp-helios", "Helios", "HashiCorp", "HashiCorp's open-source design system used across Terraform, Vault, Consul, and HashiCorp Cloud.", listOf("#0c0c0e", "#fafafa", "#3b3d45", "#1c345f")),
        System("linear", "Linear", "Linear", "The opinionated productivity-tool visual language whose keyboard-first command bar and motion timing became the template for indie SaaS.", listOf("#08090a", "#f7f8f8", "#8a8f98", "#5e6ad2")),
        System("vercel-geist", "Geist", "Vercel", "Vercel's design system — the visual language behind the Vercel dashboard, marketing, and Next.js docs.", listOf("#000000", "#ededed", "#0070f3", "#333333")),
        System("redhat-patternfly", "PatternFly", "Red Hat", "Red Hat's open-source design system for enterprise web applications and operator consoles.", listOf("#06c", "#151515", "#fff", "#c9190b")),
        System("liferay-lexicon", "Lexicon", "Liferay", "Liferay's design language for digital experience platforms.", emptyList()),
        System("stackoverflow-design", "Stacks (Stack Overflow Design System)", "Stack Overflow", "Stack Overflow's open-source design system documenting the patterns behind Stack Overflow and Teams.", emptyList()),
        System("khan-wonder-blocks", "Wonder Blocks", "Khan Academy", "Khan Academy's open-source React component library for building consistent learning experiences.", emptyList()),
        System("helsinki-hds", "Helsinki Design System (HDS)", "City of Helsinki", "Open-source design system for the City of Helsinki's digital services.", emptyList()),
        System("ontario-design", "Ontario Design System", "Government of Ontario", "Design system for Ontario.ca and the Government of Ontario's digital services.", emptyList()),
        System("cisco-momentum", "Momentum", "Cisco / Webex", "Cisco's shared design language and component libraries powering the Webex suite of collaboration products across web.", emptyList()),
        System("sap-fiori", "SAP Fiori", "SAP", "SAP's enterprise design language and guidelines, implemented through UI5 web components for consistent business application interfaces.", emptyList()),
        System("porsche-design", "Porsche Design System", "Porsche", "Porsche's branded system of web components with React, Angular, and Vue wrappers plus UX guidelines.", emptyList()),
        System("seek-braid", "Braid", "SEEK", "SEEK's themeable React component library built with vanilla-extract, emphasizing simple, composable, accessible building blocks.", emptyList()),
        System("kiwi-orbit", "Orbit", "Kiwi.com", "Kiwi.com's open-source React component library and pattern guidelines tailored for building travel booking interfaces.", emptyList()),
        System("nordhealth-nord", "Nord", "Nordhealth", "Nordhealth's framework-agnostic system of Lit web components, tokens, and themes for healthcare software products.", emptyList()),
        System("sprout-seeds", "Seeds", "Sprout Social", "Sprout Social's design system documentation, tokens, and Figma resources supporting their social media management platform.", emptyList()),
        System("splunk-ui", "Splunk UI", "Splunk", "Splunk's React component toolkit and design language for building consistent enterprise data and dashboard interfaces.", emptyList()),
        System("firefox-acorn", "Acorn", "Mozilla / Firefox", "Mozilla's public design system documenting foundations, components, and patterns for the Firefox desktop and mobile browsers.", emptyList()),
        System("instructure-ui", "Instructure UI", "Instructure (Canvas LMS)", "Instructure's accessible React component library and design tokens underpinning the Canvas learning management platform.", emptyList()),
        System("priceline-one", "Priceline One", "Priceline", "Priceline's actively maintained React component library built with styled-system, styled-components, and TypeScript.", emptyList()),
        System("okta-odyssey", "Odyssey", "Okta", "Okta's accessible design system of MUI-based React components and design tokens for building Okta product UIs.", emptyList()),
        System("wordpress-components", "WordPress Components", "WordPress / Automattic", "The @wordpress/components React library providing the reusable UI primitives behind the WordPress block editor.", emptyList()),
        System("decathlon-vitamin", "Vitamin", "Decathlon", "Decathlon's open-source design system offering React, Vue, and Svelte components plus Tailwind-based CSS and tokens.", emptyList()),
        System("ikea-skapa", "Skapa", "IKEA", "IKEA's internal design system spanning web, iOS, Android, and in-store devices.", emptyList()),
        System("mongodb-leafygreen", "LeafyGreen UI", "MongoDB", "MongoDB's open-source React design system and component library for its product and documentation surfaces.", listOf("#00ed64", "#1c2d38", "#db3030", "#001e2b")),
        System("cloudflare-kumo", "Kumo", "Cloudflare", "Cloudflare's open-source React component library, built on Base UI and Tailwind, for its dashboard and developer-facing products.", emptyList()),
        System("oracle-redwood", "Redwood", "Oracle", "Oracle's UX design language and Oracle JET-based theme unifying the interface across its Fusion Cloud Applications.", emptyList()),
        System("servicenow-horizon", "Horizon", "ServiceNow", "ServiceNow's design system for the Now Platform, providing components, patterns, and official Figma libraries for building enterprise experiences.", emptyList()),
        System("hubspot-canvas", "Canvas", "HubSpot", "HubSpot's design system for its CRM and marketing products, including a public component showcase and a React-based SDK for third-party app developers.", emptyList()),
        System("telekom-scale", "Scale", "Deutsche Telekom", "Deutsche Telekom's open-source design system for digital products, built on Stencil web components.", listOf("#ffffff", "#2238df", "#e82010")),
        System("norway-designsystemet", "Designsystemet", "Digdir (Norwegian Digitalisation Agency)", "Norway's cross-government design system, providing shared components, CSS, and tokens for public digital services.", listOf("#0062ba", "#068718", "#c01b1b")),
        System("denmark-dkfds", "DKFDS", "Danish Government", "Denmark's shared design system for public self-service solutions, jointly run by the country's digital-government and business agencies.", listOf("#454545", "#358000", "#cc0000", "#004d99")),
        System("nl-design-system", "NL Design System", "ICTU, on behalf of the Dutch Ministry of the Interior (BZK)", "", listOf("#005ea5", "#ffb612", "#dc3545")),
        System("nz-design-system", "New Zealand Government Design System", "Department of Internal Affairs, New Zealand Government", "An alpha-stage, all-of-government design system providing shared HTML/CSS/React/Vue components and content guidance for New Zealand public-sector digital services.", listOf("#ffffff", "#2a2a2a", "#005ea5", "#b10e1e")),
        System("siemens-ix", "Siemens iX", "Siemens", "Siemens' MIT-licensed design system for industrial software, shipping Stencil web components with React, Angular, and Vue bindings.", emptyList()),
        System("telefonica-mistica", "Mística", "Telefónica", "Telefónica's open design system for its digital products, with React, iOS, and Android implementations sharing one token set.", emptyList()),
        System("canada-gc-design-system", "GC Design System", "Canadian Digital Service", "The Canadian Digital Service's open-source web-component design system for Government of Canada services — distinct from, and newer than, the Canada.ca design system.", emptyList()),
        System("sbb-lyne", "Lyne", "SBB (Swiss Federal Railways)", "Swiss Federal Railways' MIT-licensed design system, shipping Lit-based web components with React wrappers for the SBB digital estate.", emptyList()),
        System("line-design-system", "LINE Design System", "LINE", "LINE's public design guidelines for its messenger and global services — foundations, components, and interaction patterns, documented without a public component library.", emptyList()),
        System("jpmorgan-salt", "Salt", "J.P. Morgan", "J.P. Morgan's Apache-2.0 React design system, built around accessibility, theming, and the multiple UI densities financial interfaces need.", emptyList()),
        System("deutschebahn-db-ux", "DB UX Design System", "Deutsche Bahn", "Deutsche Bahn's Apache-2.0 design system for its web estate, shipping React, Angular, Vue, web-component, and CSS-only builds from one core.", emptyList()),
        System("argentina-poncho", "Poncho", "Government of Argentina", "The official style and component library for designing and building the websites and mobile apps of the Argentine national government.", listOf("#232d4f", "#e3e7ed", "#e7ba61", "#c2185b")),
        System("vtex-shoreline", "Shoreline", "VTEX", "VTEX Design System for back-office experiences.", emptyList()),
        System("vtex-styleguide", "VTEX Styleguide", "VTEX", "The VTEX Design System and React component library.", emptyList()),
        System("tencent-tdesign", "TDesign", "Tencent", "Tencent's open-source enterprise design system: one set of values, a consistent design language and visual style, and component libraries that work out of the box.", listOf("#0052d9", "#003cab", "#f2f3ff")),
        System("bytedance-semi", "Semi Design", "ByteDance", "An easy-to-customize modern design system that helps designers and developers create high-quality products.", emptyList()),
        System("alibaba-fusion", "Fusion Design", "Alibaba", "An enterprise-level solution for building web products by improving designer-developer collaboration, product experience consistency, and development efficiency.", emptyList()),
        System("youzan-vant", "Vant", "Youzan", "A lightweight, customizable Vue UI library for mobile web apps.", emptyList()),
        System("element-plus", "Element Plus", "Element Plus", "A Vue 3 based component library for designers and developers.", emptyList()),
        System("naive-ui", "Naive UI", "TuSimple", "A Vue 3 component library — fairly complete, theme-customizable, written in TypeScript, and fast.", emptyList()),
        System("cyberagent-spindle", "Spindle", "CyberAgent", "The conventions everyone building Ameba uses to create a recognisably Ameba feel, along with the tools and guidelines that help them do it.", listOf("#2d8c3c", "#298737", "#f5f6f6", "#ffffff")),
        System("smarthr-design", "SmartHR Design System", "SmartHR", "A design system for expressing SmartHR's character — usable by anyone, efficient, and without second-guessing.", emptyList()),
        System("japan-dads", "Design System for Digital Agency", "Digital Agency, Government of Japan", "", emptyList()),
        System("nysds", "New York State Design System", "New York State ITS", "The New York State Design System makes it easier to build accessible, mobile-friendly applications and websites for New York State.", emptyList()),
    )

    private val alias = mapOf(
        "material" to "material-design",
        "material 3" to "material-design",
        "material3" to "material-design",
        "m3" to "material-design",
        "google" to "material-design",
        "apple" to "apple-hig",
        "hig" to "apple-hig",
        "wwdc" to "apple-hig",
        "shopify" to "polaris",
        "ibm" to "carbon-design",
        "carbon" to "carbon-design",
        "stripe" to "stripe-design",
        "github" to "primer",
        "microsoft" to "fluent-design",
        "salesforce" to "lightning-design",
    )

    fun find(query: String): List<System> {
        val words = query.trim().lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        if (words.length < 2) return emptyList()
        alias[words]?.let { slug -> return systems.filter { it.slug == slug } }
        val compact = words.replace(" ", "")
        return systems.map { system -> system to score(system, words, compact) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(5)
            .map { it.first }
    }

    fun format(query: String, hits: List<System>): String {
        if (hits.isEmpty()) {
            return "No design system matched. The gallery is https://www.designsystems.one/design-systems"
        }
        return buildString {
            append("Matches for \"").append(query.trim()).append("\".\n")
            hits.forEach { system ->
                append(system.name)
                if (system.vendor.isNotEmpty()) append(" (").append(system.vendor).append(")")
                append('\n')
                if (system.note.isNotEmpty()) append(system.note).append('\n')
                if (system.swatches.isEmpty()) append("No swatch on the card.\n")
                else append("Gallery swatches, not a full token set: ").append(system.swatches.joinToString(", ")).append('\n')
                append("https://www.designsystems.one/design-systems/").append(system.slug).append('\n')
            }
            append("Quote only these swatches or a value printed on that page. Do not invent a hex. Do not copy the company product.\n")
            append("Color, type, and spacing primers: https://www.designsystems.one/foundations")
        }
    }

    private fun score(system: System, words: String, compact: String): Int {
        val name = system.name.lowercase()
        val vendor = system.vendor.lowercase()
        val slug = system.slug.lowercase()
        val nameCompact = name.replace(Regex("[^a-z0-9]"), "")
        return when {
            slug == compact || name == words || vendor == words -> 100
            nameCompact == compact -> 90
            slug.startsWith(compact) || name.startsWith(words) -> 70
            name.contains(words) || slug.contains(compact) || vendor.contains(words) -> 40
            else -> 0
        }
    }
}
