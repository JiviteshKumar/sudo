package com.technewz.app.util

import java.util.Locale

/**
 * Deterministic skill matching. The score shown on job cards is computed locally from a fixed
 * vocabulary so it is explainable (you can always see which skills matched and which are missing).
 */
object Skills {
    // canonical name -> alternative spellings (all lowercase)
    private val vocab: Map<String, List<String>> = linkedMapOf(
        "Python" to listOf("python"),
        "Java" to listOf("java"),
        "Kotlin" to listOf("kotlin"),
        "JavaScript" to listOf("javascript", "js", "ecmascript"),
        "TypeScript" to listOf("typescript"),
        "C++" to listOf("c++", "cpp"),
        "C#" to listOf("c#", ".net", "dotnet"),
        "C" to listOf("c programming", "embedded c"),
        "Go" to listOf("golang", "go lang"),
        "Rust" to listOf("rust"),
        "Swift" to listOf("swift"),
        "Scala" to listOf("scala"),
        "R" to listOf("r programming", "rstudio", "tidyverse"),
        "PHP" to listOf("php", "laravel"),
        "Ruby" to listOf("ruby", "rails"),
        "SQL" to listOf("sql", "t-sql", "pl/sql"),
        "MATLAB" to listOf("matlab"),
        "React" to listOf("react", "reactjs", "react.js"),
        "React Native" to listOf("react native"),
        "Next.js" to listOf("next.js", "nextjs"),
        "Angular" to listOf("angular"),
        "Vue" to listOf("vue", "vue.js", "vuejs"),
        "Node.js" to listOf("node.js", "nodejs"),
        "HTML/CSS" to listOf("html", "css", "tailwind"),
        "Django" to listOf("django"),
        "Flask" to listOf("flask"),
        "FastAPI" to listOf("fastapi"),
        "Spring" to listOf("spring boot", "spring framework"),
        "Android" to listOf("android", "jetpack compose"),
        "iOS" to listOf("ios", "swiftui"),
        "Flutter" to listOf("flutter", "dart"),
        "GraphQL" to listOf("graphql"),
        "REST APIs" to listOf("rest api", "restful", "rest apis"),
        "Microservices" to listOf("microservices", "microservice"),
        "Git" to listOf("git", "github", "gitlab"),
        "Linux" to listOf("linux", "unix", "bash", "shell scripting"),
        "Docker" to listOf("docker", "containers", "containerization"),
        "Kubernetes" to listOf("kubernetes", "k8s"),
        "Terraform" to listOf("terraform", "infrastructure as code"),
        "CI/CD" to listOf("ci/cd", "continuous integration", "github actions", "jenkins"),
        "AWS" to listOf("aws", "amazon web services", "ec2", "s3", "lambda", "sagemaker"),
        "GCP" to listOf("gcp", "google cloud", "bigquery", "vertex ai"),
        "Azure" to listOf("azure"),
        "PostgreSQL" to listOf("postgresql", "postgres"),
        "MySQL" to listOf("mysql"),
        "MongoDB" to listOf("mongodb", "mongo"),
        "Redis" to listOf("redis"),
        "Kafka" to listOf("kafka"),
        "Spark" to listOf("spark", "pyspark"),
        "Hadoop" to listOf("hadoop", "hive"),
        "Airflow" to listOf("airflow"),
        "dbt" to listOf("dbt"),
        "Snowflake" to listOf("snowflake"),
        "Databricks" to listOf("databricks"),
        "ETL" to listOf("etl", "elt", "data pipelines", "data pipeline"),
        "Machine Learning" to listOf("machine learning", "ml"),
        "Deep Learning" to listOf("deep learning", "neural networks", "neural network"),
        "NLP" to listOf("nlp", "natural language processing"),
        "Computer Vision" to listOf("computer vision", "opencv", "image processing"),
        "LLMs" to listOf("llm", "llms", "large language model", "large language models", "gpt", "rag", "prompt engineering"),
        "Generative AI" to listOf("generative ai", "genai", "diffusion"),
        "Reinforcement Learning" to listOf("reinforcement learning", "rlhf"),
        "PyTorch" to listOf("pytorch", "torch"),
        "TensorFlow" to listOf("tensorflow", "keras"),
        "JAX" to listOf("jax"),
        "scikit-learn" to listOf("scikit-learn", "sklearn", "scikit learn"),
        "Hugging Face" to listOf("hugging face", "huggingface", "transformers"),
        "LangChain" to listOf("langchain", "llamaindex"),
        "Pandas" to listOf("pandas"),
        "NumPy" to listOf("numpy"),
        "Statistics" to listOf("statistics", "statistical", "hypothesis testing", "a/b testing"),
        "Data Analysis" to listOf("data analysis", "data analytics", "exploratory data analysis", "eda"),
        "Data Visualization" to listOf("data visualization", "matplotlib", "seaborn", "plotly", "d3"),
        "Tableau" to listOf("tableau"),
        "Power BI" to listOf("power bi", "powerbi"),
        "Excel" to listOf("excel", "spreadsheets"),
        "MLOps" to listOf("mlops", "mlflow", "kubeflow", "model deployment"),
        "System Design" to listOf("system design", "distributed systems", "scalability"),
        "Data Structures & Algorithms" to listOf("data structures", "algorithms", "dsa"),
        "Security" to listOf("cybersecurity", "security engineering", "penetration testing", "owasp"),
        "Figma" to listOf("figma"),
        "Agile" to listOf("agile", "scrum", "jira"),
    )

    private val patterns: Map<String, List<Regex>> = vocab.mapValues { (_, alts) ->
        alts.map { alt ->
            Regex("(?<![a-z0-9+#.])" + Regex.escape(alt) + "(?![a-z0-9+#])", RegexOption.IGNORE_CASE)
        }
    }

    /** Canonical skills mentioned in free text. */
    fun extract(text: String): Set<String> {
        val t = text.lowercase(Locale.ROOT)
        return patterns.filter { (_, rs) -> rs.any { it.containsMatchIn(t) } }.keys
    }

    /** Normalise a user's skill list (from resume or manual entry) to canonical names. */
    fun normalize(userSkills: List<String>): Set<String> {
        val joined = userSkills.joinToString(" , ")
        val canon = extract(joined).toMutableSet()
        // keep user skills that are not in vocabulary so they still count for exact mentions
        return canon
    }

    data class Match(val score: Int?, val matched: List<String>, val missing: List<String>)

    fun match(userSkills: Set<String>, jobText: String): Match {
        val required = extract(jobText)
        if (required.isEmpty() || userSkills.isEmpty()) return Match(null, emptyList(), required.toList().take(8))
        val matched = required.intersect(userSkills)
        val missing = required - userSkills
        // Diminishing penalty for long "nice to have" lists: cap denominator.
        val denom = minOf(required.size, 10).coerceAtLeast(1)
        val score = ((matched.size.toDouble() / denom) * 100).toInt().coerceIn(0, 100)
        return Match(score, matched.toList(), missing.toList().take(10))
    }
}

/** Heuristic red flags for fraudulent postings. */
object ScamFilter {
    private val rules = listOf(
        Regex("registration fee|training fee|processing fee|security deposit|pay (a|the) fee", RegexOption.IGNORE_CASE) to "Asks for payment",
        Regex("(contact|message|text|reach|apply|ping|dm)( us| me| the recruiter)? (on|via|through|at) (whats ?app|telegram)", RegexOption.IGNORE_CASE) to "Contact via WhatsApp/Telegram",
        Regex("no interview|without interview", RegexOption.IGNORE_CASE) to "No interview",
        Regex("earn \\$?\\d+[,\\d]* ?(per|a|/) ?(day|hour) from home", RegexOption.IGNORE_CASE) to "Too-good pay claim",
        Regex("send (your )?(bank|aadhaar|ssn|passport)", RegexOption.IGNORE_CASE) to "Asks for ID/bank details",
        Regex("(send|email|mail|forward)[^.]{0,40}(cv|resume|application)[^.]{0,30}@(gmail|yahoo|outlook|hotmail)\\.com", RegexOption.IGNORE_CASE) to "Personal email contact",
    )

    fun flags(text: String): List<String> = rules.filter { it.first.containsMatchIn(text) }.map { it.second }
}
