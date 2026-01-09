import streamlit as st
import requests
import json

# 1. PASTE YOUR API GATEWAY URL HERE
# API_URL = "https://uvgbgvrkcg.execute-api.us-east-1.amazonaws.com/dev/query"
API_URL = "https://hipwbthncg.execute-api.us-east-1.amazonaws.com/dev/query"
st.title("Text2SQL Sprint 1")

# 2. Input Box
query = st.text_input("Ask a question:", "Show me total sales")

# 3. Button
if st.button("Run Query"):
    with st.spinner("Agents are working..."):
        try:
            # Send data to AWS
            payload = {"user_query": query}
            response = requests.post(API_URL, json=payload)

            print("RAW API RESPONSE:", response.text)  # Debugging line
            print("STATUS CODE:", response.status_code)  # Debugging line
            if response.status_code == 200:
                result = response.json()
                st.success("Success!")
                st.subheader("Generated SQL")
                st.code(result.get("generated_sql", ""), language="sql")
                st.text(f"Confidence: {result.get('confidence_score', 0)}")

                st.subheader("Data Result")
                execution_result = result.get("execution_result", {})

                if isinstance(execution_result, dict) and "rows" in execution_result:
                    st.json(execution_result["rows"])
                else:
                    st.json(execution_result)

                with st.expander("Debug Agent State"):
                    st.json(result)
            else:
                st.error(f"Error: {response.status_code}")
                st.code(response.text)

        except Exception as e:
            st.error(f"Connection Error: {e}")